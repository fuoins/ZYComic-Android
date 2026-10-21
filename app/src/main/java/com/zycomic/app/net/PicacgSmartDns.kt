package com.zycomic.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Optional SmartDNS lookup. It is intentionally isolated from Picacg routing and uses system DNS. */
object PicacgSmartDns {
    private const val DOMAIN = "picaapi.picacomic.com"
    private const val PATH = "/app/picacomic/dns/resolve?domain=$DOMAIN"
    private const val MAX_BODY_BYTES = 64 * 1024
    private const val MAX_LINES = 32
    private const val MAX_IP_CANDIDATES = 64

    private val endpoints = listOf("https://macapi1.com$PATH", "https://macapi3.top$PATH")

    /** A separate, unpinned client: platform trust and normal system DNS remain in effect. */
    private val client = OkHttpClient.Builder()
        .dns(Dns.SYSTEM)
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun fetch(): List<String> = withContext(Dispatchers.IO) {
        for (endpoint in endpoints) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", "okhttp/3.8.1")
                    .header("Accept", "application/json")
                    .get()
                    .build()
                val parsed = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val body = response.body ?: return@use emptyList()
                    if (body.contentLength() > MAX_BODY_BYTES) return@use emptyList()
                    val text = body.byteStream().use(::readBoundedBody) ?: return@use emptyList()
                    parse(text)
                }
                if (parsed.isNotEmpty()) return@withContext parsed
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Try the secondary HTTPS resolver; failure is deliberately non-fatal.
            }
        }
        emptyList()
    }

    /** Parses only a successful response for the one requested domain. No network access occurs here. */
    fun parse(body: String): List<String> {
        if (body.length > MAX_BODY_BYTES) return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (!isSuccessfulCode(root.opt("code"))) return emptyList()

        val data = root.opt("data") as? JSONObject ?: return emptyList()
        val domain = data.opt("domain") as? String ?: return emptyList()
        if (!domain.equals(DOMAIN, ignoreCase = true)) return emptyList()

        val lines = data.opt("lines") as? JSONObject ?: return emptyList()
        if (lines.length() > MAX_LINES) return emptyList()

        val polluted = linkedSetOf<String>()
        val candidates = linkedSetOf<String>()
        val lineNames = lines.keys()
        while (lineNames.hasNext()) {
            val line = lines.opt(lineNames.next()) as? JSONObject ?: return emptyList()
            val pollutedValue = line.opt("pollutedIps")
            if (pollutedValue != null && pollutedValue !== JSONObject.NULL) {
                val pollutedArray = pollutedValue as? JSONArray ?: return emptyList()
                if (polluted.size + pollutedArray.length() > MAX_IP_CANDIDATES) return emptyList()
                for (index in 0 until pollutedArray.length()) {
                    val ip = pollutedArray.opt(index) as? String ?: return emptyList()
                    if (isValidIpv4(ip)) polluted += ip
                }
            }

            val status = line.opt("status") as? String ?: continue
            if (!status.equals("ok", ignoreCase = true)) continue
            val ips = line.opt("ips") as? JSONArray ?: return emptyList()
            for (ipIndex in 0 until ips.length()) {
                val ip = ips.opt(ipIndex) as? String ?: return emptyList()
                if (candidates.size >= MAX_IP_CANDIDATES && ip !in candidates) return emptyList()
                candidates += ip
            }
        }

        return candidates.asSequence()
            .filter(::isPublicIpv4)
            .filterNot { it in polluted }
            .toList()
    }

    private fun isSuccessfulCode(value: Any?): Boolean = value is Number && value.toDouble() == 200.0

    private fun readBoundedBody(input: java.io.InputStream): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BODY_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

/** Strict literal-only public IPv4 validation shared by SmartDNS and the built-in rule merger. */
internal fun isPublicIpv4(address: String): Boolean {
    if (!isValidIpv4(address)) return false
    val octets = address.split('.').map(String::toInt)
    val first = octets[0]
    val second = octets[1]
    val third = octets[2]
    return when {
        first == 0 || first == 10 || first == 127 -> false
        first == 100 && second in 64..127 -> false // Shared address space.
        first == 169 && second == 254 -> false
        first == 172 && second in 16..31 -> false
        first == 192 && second == 0 && third == 0 -> false // IETF protocol assignments.
        first == 192 && second == 0 && third == 2 -> false // Documentation.
        first == 192 && second == 88 && third == 99 -> false // Deprecated 6to4 relay anycast.
        first == 192 && second == 168 -> false
        first == 198 && second in 18..19 -> false // Benchmarking.
        first == 198 && second == 51 && third == 100 -> false // Documentation.
        first == 203 && second == 0 && third == 113 -> false // Documentation.
        first >= 224 -> false // Multicast and reserved space.
        else -> true
    }
}

private fun isValidIpv4(address: String): Boolean {
    val octets = address.split('.')
    return octets.size == 4 && octets.all { part ->
        part.isNotEmpty() && (part == "0" || !part.startsWith('0')) &&
            part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true
    } && address.all { it.code < 128 }
}
