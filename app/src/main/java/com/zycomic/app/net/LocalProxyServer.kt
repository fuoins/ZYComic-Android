package com.zycomic.app.net

import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 本地 HTTP 代理服务器（方案A）。
 *
 * 工作原理：
 * 1. 监听 127.0.0.1:port，所有 OkHttp 流量走此代理。
 * 2. CONNECT 请求：
 *    - 若 host 在 [sniDomains] 中 → MITM 模式：代理直连 IP（不发 SNI，信任所有证书），
 *      然后用自签名证书与客户端做 TLS 握手，双向转发解密后的字节流。
 *    - 否则 → 隧道模式：直接 TCP 转发，不做 MITM。
 * 3. 普通 HTTP 请求：解析完整 URL，直连目标服务器转发。
 *
 * 每个连接用一个线程处理，Socket 操作为阻塞 IO，不使用协程。
 */
class LocalProxyServer(
    private val port: Int,
    @Volatile private var rule: Map<String, List<String>>,
    @Volatile private var sniDomains: Set<String>,
) {
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    /** 动态更新 rule（不需要重启代理）。 */
    fun updateRule(newRule: Map<String, List<String>>) {
        rule = newRule
        Log.d(TAG, "updateRule: ${newRule.size} domains")
    }

    /** 动态更新 sniDomains（不需要重启代理）。 */
    fun updateSniDomains(newSni: Set<String>) {
        sniDomains = newSni
        Log.d(TAG, "updateSniDomains: ${newSni.size} domains")
    }

    /** 自签名证书 SSLContext，用于客户端→代理的 TLS 握手。 */
    private val serverSSLContext: SSLContext

    /** 信任所有证书的 SSLContext，用于代理→上游服务器的 TLS 连接。 */
    private val trustAllSSLContext: SSLContext

    companion object {
        private const val TAG = "LocalProxy"
    }

    /** 多 IP 轮询索引（resolveHostIp 用）。 */
    private val ipPollIndex = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        Security.addProvider(BouncyCastleProvider())
        serverSSLContext = generateSelfSignedSSLContext()
        trustAllSSLContext = createTrustAllSSLContext()
    }

    // ==================== 生命周期 ====================

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", port))
                }
                Log.d(TAG, "Proxy server started on 127.0.0.1:$port")

                while (running) {
                    try {
                        val client = serverSocket!!.accept()
                        Thread {
                            try {
                                handleClient(client)
                            } catch (e: Exception) {
                                Log.w(TAG, "handleClient error: ${e.message}")
                            } finally {
                                try { client.close() } catch (_: Exception) {}
                            }
                        }.start()
                    } catch (e: IOException) {
                        if (running) Log.w(TAG, "accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxy server", e)
                running = false
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "Proxy server stopped")
    }

    // ==================== 客户端请求处理 ====================

    private fun handleClient(clientSocket: Socket) {
        val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))

        // 读取请求行
        val requestLine = try {
            reader.readLine() ?: return
        } catch (e: Exception) {
            return
        }

        // 读取所有 header
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = try { reader.readLine() ?: break } catch (e: Exception) { break }
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }

        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]

        Log.d(TAG, "Request: $method $target")

        if (method.equals("CONNECT", ignoreCase = true)) {
            // target 格式: host:port，兼容带 scheme 的写法，例如 CONNECT https://newmwimserv6.cc HTTP/1.1
            var t = target
            // 去掉 scheme 前缀（如有）
            if (t.startsWith("http://", ignoreCase = true)) {
                t = t.substring("http://".length)
            } else if (t.startsWith("https://", ignoreCase = true)) {
                t = t.substring("https://".length)
            }
            val colonIdx = t.lastIndexOf(':')
            val host = if (colonIdx > 0) t.substring(0, colonIdx) else t
            val portNum = if (colonIdx > 0) t.substring(colonIdx + 1).toIntOrNull() ?: 443 else 443
            handleConnect(clientSocket, host, portNum)
        } else {
            // 普通 HTTP 请求（完整 URL: http://host/path）
            handleHttp(clientSocket, method, target, headers)
        }
    }

    // ==================== CONNECT 处理 ====================

    private fun handleConnect(clientSocket: Socket, host: String, port: Int) {
        val mitm = sniDomains.contains(host)
        Log.d(TAG, "CONNECT $host:$port, mitm=$mitm")
        if (mitm) {
            handleConnectMitm(clientSocket, host, port)
        } else {
            handleConnectTunnel(clientSocket, host, port)
        }
    }

    /**
     * MITM 模式：
     * 1. 用 rule 中的 IP 直连上游服务器，建立 TLS（不发 SNI，信任所有证书）。
     * 2. 回复客户端 "200 Connection Established"。
     * 3. 用自签名证书包装 clientSocket，与客户端做 TLS 握手。
     * 4. 在两个 SSLSocket 之间双向转发字节。
     */
    private fun handleConnectMitm(clientSocket: Socket, host: String, port: Int) {
        var upstreamSocket: SSLSocket? = null
        var clientSSL: SSLSocket? = null
        var stage = "upstream"

        try {
            // 1. 建立到上游服务器的 TLS 连接（IP 直连，不发 SNI）
            val ip = resolveHostIp(host) ?: host
            Log.d(TAG, "Resolved IP: $ip for $host")
            val sslFactory = trustAllSSLContext.socketFactory as SSLSocketFactory
            upstreamSocket = sslFactory.createSocket() as SSLSocket

            // 必须在 connect() 之前移除 SNI，connect 之后设置无效
            removeSni(upstreamSocket)

            val upStart = System.nanoTime()
            upstreamSocket.connect(InetSocketAddress(ip, port), 5000)
            upstreamSocket.startHandshake()
            val upElapsed = (System.nanoTime() - upStart) / 1_000_000
            Log.d(TAG, "Upstream connected: $ip:$port in ${upElapsed}ms")

            // 2. 回复客户端
            clientSocket.getOutputStream().apply {
                write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                flush()
            }

            // 3. 用自签名证书包装客户端 socket，做服务端 TLS 握手
            stage = "client_handshake"
            Log.d(TAG, "Client handshake started: $host")
            clientSSL = serverSSLContext.socketFactory
                .createSocket(clientSocket, null, clientSocket.port, true) as SSLSocket
            clientSSL.useClientMode = false
            clientSSL.startHandshake()
            Log.d(TAG, "Client handshake completed: $host")

            // 4. 双向转发
            stage = "pipe"
            val in1 = clientSSL.inputStream
            val out1 = upstreamSocket.outputStream
            val in2 = upstreamSocket.inputStream
            val out2 = clientSSL.outputStream

            val t1 = Thread { pipe(in1, out1, "$host client->upstream") }
            val t2 = Thread { pipe(in2, out2, "$host upstream->client") }
            t1.start()
            t2.start()
            t1.join()
            t2.join()
        } catch (e: Exception) {
            when (stage) {
                "upstream" -> Log.w(TAG, "Upstream error: ${e.message}")
                "client_handshake" -> Log.w(TAG, "Client handshake error: ${e.message}")
                else -> Log.w(TAG, "pipe error: ${e.message}")
            }
        } finally {
            try { clientSSL?.close() } catch (_: Exception) {}
            try { upstreamSocket?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    /**
     * 隧道模式：直接 TCP 转发，不做 MITM。
     */
    private fun handleConnectTunnel(clientSocket: Socket, host: String, port: Int) {
        var upstream: Socket? = null
        try {
            val ip = resolveHostIp(host) ?: host
            upstream = Socket()
            upstream.connect(InetSocketAddress(ip, port), 5000)
            Log.d(TAG, "Tunnel connected: $host:$port via $ip")

            clientSocket.getOutputStream().apply {
                write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                flush()
            }

            val t1 = Thread { pipe(clientSocket.inputStream, upstream.outputStream, "$host client->upstream") }
            val t2 = Thread { pipe(upstream.inputStream, clientSocket.outputStream, "$host upstream->client") }
            t1.start()
            t2.start()
            t1.join()
            t2.join()
        } catch (e: Exception) {
            Log.w(TAG, "handleConnectTunnel error for $host: ${e.message}")
        } finally {
            try { upstream?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    // ==================== 普通 HTTP 处理 ====================

    private fun handleHttp(clientSocket: Socket, method: String, url: String, headers: Map<String, String>) {
        var upstream: Socket? = null
        try {
            // 解析 URL: http://host:port/path
            var host: String
            var portNum = 80
            var path: String

            val noScheme = url.removePrefix("http://")
            val slashIdx = noScheme.indexOf('/')
            val authority = if (slashIdx >= 0) noScheme.substring(0, slashIdx) else noScheme
            path = if (slashIdx >= 0) noScheme.substring(slashIdx) else "/"

            val colonIdx = authority.lastIndexOf(':')
            if (colonIdx > 0) {
                host = authority.substring(0, colonIdx)
                portNum = authority.substring(colonIdx + 1).toIntOrNull() ?: 80
            } else {
                host = authority
            }

            val ip = resolveHostIp(host) ?: host
            upstream = Socket()
            upstream.connect(InetSocketAddress(ip, portNum), 5000)

            // 转发请求行 + headers
            val upOut = upstream.getOutputStream()
            val reqLine = "$method $path HTTP/1.1\r\n"
            upOut.write(reqLine.toByteArray())
            headers.forEach { (k, v) ->
                // 去掉代理相关 header
                if (!k.equals("Proxy-Connection", ignoreCase = true)) {
                    upOut.write("$k: $v\r\n".toByteArray())
                }
            }
            upOut.write("\r\n".toByteArray())
            upOut.flush()

            // 转发响应
            pipe(upstream.inputStream, clientSocket.getOutputStream())
        } catch (e: Exception) {
            Log.w(TAG, "handleHttp error: ${e.message}")
        } finally {
            try { upstream?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 解析 host -> 上游 IP。
     * 优先使用测速选出的最快 IP（[RouteManager.fastestIp]）；
     * 否则从 rule[host] 列表中轮询取一个；
     * 都没有则返回 null（调用方回退为直连 host 本身）。
     */
    private fun resolveHostIp(host: String): String? {
        // 优先使用测速选出的最快 IP
        RouteManager.fastestIp[host]?.let {
            Log.d(TAG, "resolveHostIp: $host -> $it (fastestIp)")
            return it
        }
        // 回退：rule 轮询
        val ips = rule[host] ?: return null
        if (ips.isEmpty()) return null
        val idx = ipPollIndex.getAndIncrement() % ips.size
        val ip = ips[idx]
        Log.d(TAG, "resolveHostIp: $host -> $ip (rule/poll idx=$idx/${ips.size})")
        return ip
    }

    /** 8KB 缓冲区双向转发。tag 用于日志标识方向。 */
    private fun pipe(input: InputStream, output: OutputStream, tag: String = "") {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: IOException) {
            // 连接断开，正常结束
        } catch (e: Exception) {
            Log.w(TAG, "pipe error ($tag): ${e.message}")
        }
    }

    /**
     * 移除上游 SSLSocket 的 SNI（Server Name Indication）。
     * 必须在 [SSLSocket.connect] 之前调用，connect 之后设置无效。
     * 用 null 比 emptyList() 更可靠；失败时反射兜底。
     */
    private fun removeSni(socket: SSLSocket) {
        try {
            val params = socket.sslParameters
            params.serverNames = null
            socket.sslParameters = params
        } catch (e: Exception) {
            Log.d(TAG, "removeSni set sslParameters failed: ${e.message}, fallback reflection")
            // 兜底：反射设置
            try {
                val field = socket.javaClass.getDeclaredField("serverNames")
                field.isAccessible = true
                field.set(socket, null)
            } catch (_: Exception) {
            }
        }
    }

    // ==================== SSL 上下文生成 ====================

    /**
     * 生成自签名证书的 SSLContext（用于客户端→代理的 TLS）。
     * CN=* 通配，RSA 2048，有效期 10 年。
     */
    private fun generateSelfSignedSSLContext(): SSLContext {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }.generateKeyPair()

        val issuer = X500Name("CN=*, O=ZYComic Proxy, C=CN")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date(System.currentTimeMillis() - 86400000L)
        val notAfter = Date(System.currentTimeMillis() + 3650L * 86400000L)

        val certBuilder = JcaX509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, issuer,
            keyPair.public,
        )
        val contentSigner = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider(BouncyCastleProvider())
            .build(keyPair.private)
        val certHolder = certBuilder.build(contentSigner)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider())
            .getCertificate(certHolder)

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("proxy", keyPair.private, "password".toCharArray(), arrayOf(cert))
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, "password".toCharArray())
        }

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, SecureRandom())
        }
    }

    /** 信任所有证书的 SSLContext（用于代理→上游服务器的 TLS）。 */
    private fun createTrustAllSSLContext(): SSLContext {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        return SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }
    }
}
