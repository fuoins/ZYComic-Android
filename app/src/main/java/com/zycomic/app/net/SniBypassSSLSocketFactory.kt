package com.zycomic.app.net

import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.net.InetAddress
import java.net.Socket

/**
 * SNI 绕过 SSLSocketFactory：包装默认 SSLSocketFactory，对需要 SNI 绕过的域名清除 SNI。
 *
 * 行为：
 * - 所有 createSocket 方法委托给 delegate 创建 socket。
 * - 如果返回的是 SSLSocket，且 host 在 [RouteManager.isSniBypass] 列表中，则清除 SNI（serverNames 置空）。
 * - String 类型 host 才能判断是否需要 SNI 绕过；InetAddress 类型无法反查域名，跳过。
 */
class SniBypassSSLSocketFactory(
    private val delegate: SSLSocketFactory,
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(): Socket {
        return delegate.createSocket()
    }

    override fun createSocket(host: String, port: Int): Socket {
        val socket = delegate.createSocket(host, port)
        return maybeClearSni(socket, host)
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        val socket = delegate.createSocket(host, port, localHost, localPort)
        return maybeClearSni(socket, host)
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        val socket = delegate.createSocket(host, port)
        // InetAddress 无法反查原始域名，跳过 SNI 绕过
        return socket
    }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        val socket = delegate.createSocket(address, port, localAddress, localPort)
        // InetAddress 无法反查原始域名，跳过 SNI 绕过
        return socket
    }

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        val socket = delegate.createSocket(s, host, port, autoClose)
        return maybeClearSni(socket, host)
    }

    /** 如果是 SSLSocket 且 host 需要 SNI 绕过，则清除 SNI。 */
    private fun maybeClearSni(socket: Socket, host: String?): Socket {
        if (socket is SSLSocket && host != null && RouteManager.isSniBypass(host)) {
            val sslParams = socket.sslParameters
            sslParams.serverNames = emptyList()
            socket.sslParameters = sslParams
        }
        return socket
    }
}
