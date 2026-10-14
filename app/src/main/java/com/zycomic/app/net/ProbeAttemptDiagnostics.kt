package com.zycomic.app.net

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Safe per-attempt network event. It intentionally excludes URLs, headers, bodies, and exception messages. */
data class SpeedTestNetworkEvent(
    val event: String,
    val phase: String? = null,
    val host: String? = null,
    val proxy: String? = null,
    val proxyCandidates: List<String> = emptyList(),
    val socketAddress: String? = null,
    val resolvedAddresses: List<String> = emptyList(),
    val protocol: String? = null,
    val tlsVersion: String? = null,
    val cipherSuite: String? = null,
    val exceptionClass: String? = null,
    val causeClass: String? = null,
)

/** Client-side SNI parameter observations; these do not claim to observe the wire ClientHello. */
data class SpeedTestSniDecision(
    val host: String?,
    val peerIp: String?,
    val socketClass: String,
    val configuredDomainMatch: Boolean,
    val cloudflareExempt: Boolean,
    val decision: String,
    val serverNamesBeforeCount: Int? = null,
    val serverNamesAfterCount: Int? = null,
    val parameterReadbackEmpty: Boolean? = null,
    val failureClass: String? = null,
    val wireObserved: Boolean = false,
)

/** Per-request trace attached to the OkHttp Request tag and populated by EventListener callbacks. */
internal class ProbeAttemptTrace {
    private val phase = AtomicReference("route_selection")
    private val network = CopyOnWriteArrayList<SpeedTestNetworkEvent>()
    private val sni = CopyOnWriteArrayList<SpeedTestSniDecision>()

    fun setPhase(value: String) {
        phase.set(value)
    }

    fun currentPhase(): String = phase.get()

    fun addNetworkEvent(event: SpeedTestNetworkEvent) {
        if (network.size < MAX_EVENTS) network.add(event)
    }

    fun addSniDecision(decision: SpeedTestSniDecision) {
        if (sni.size < MAX_EVENTS) sni.add(decision)
    }

    fun networkEvents(): List<SpeedTestNetworkEvent> = network.toList()

    fun sniDecisions(): List<SpeedTestSniDecision> = sni.toList()

    fun recordProbeFailure(error: Throwable) {
        addNetworkEvent(
            SpeedTestNetworkEvent(
                event = "probe_exception",
                phase = currentPhase(),
                exceptionClass = error.javaClass.name,
                causeClass = error.cause?.javaClass?.name,
            ),
        )
    }

    companion object {
        private const val MAX_EVENTS = 32
    }
}

/** Makes the current synchronous probe trace available to the wrapped SSLSocketFactory. */
internal object ProbeAttemptTraceContext {
    private val active = ThreadLocal<ProbeAttemptTrace?>()

    fun current(): ProbeAttemptTrace? = active.get()

    fun <T> withTrace(trace: ProbeAttemptTrace, block: () -> T): T {
        val previous = active.get()
        active.set(trace)
        return try {
            block()
        } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }
}

/** Captures the effective OkHttp route and TLS outcome without retaining request or response secrets. */
internal class ProbeEventListener(
    private val trace: ProbeAttemptTrace,
) : EventListener() {
    override fun proxySelectStart(call: Call, url: HttpUrl) {
        trace.setPhase("proxy_selection")
        trace.addNetworkEvent(SpeedTestNetworkEvent(event = "proxy_select_start", phase = "proxy_selection", host = url.host))
    }

    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
        trace.addNetworkEvent(
            SpeedTestNetworkEvent(
                event = "proxy_select_end",
                phase = "proxy_selection",
                host = url.host,
                proxyCandidates = proxies.map { it.safeDescription() },
            ),
        )
        trace.setPhase("dns_lookup")
    }

    override fun dnsStart(call: Call, domainName: String) {
        trace.setPhase("dns_lookup")
        trace.addNetworkEvent(SpeedTestNetworkEvent(event = "dns_start", phase = "dns_lookup", host = domainName))
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        trace.addNetworkEvent(
            SpeedTestNetworkEvent(
                event = "dns_end",
                phase = "dns_lookup",
                host = domainName,
                resolvedAddresses = inetAddressList.map { it.hostAddress.substringBefore('%') },
            ),
        )
        trace.setPhase("tcp_connect")
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        trace.setPhase("tcp_connect")
        trace.addNetworkEvent(
            SpeedTestNetworkEvent(
                event = "connect_start",
                phase = "tcp_connect",
                host = call.request().url.host,
                proxy = proxy.safeDescription(),
                socketAddress = inetSocketAddress.safeDescription(),
            ),
        )
    }

    override fun secureConnectStart(call: Call) {
        trace.setPhase("tls_handshake")
        trace.addNetworkEvent(SpeedTestNetworkEvent(event = "tls_start", phase = "tls_handshake", host = call.request().url.host))
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        trace.setPhase("http_exchange")
        trace.addNetworkEvent(
            SpeedTestNetworkEvent(
                event = "tls_end",
                phase = "tls_handshake",
                host = call.request().url.host,
                tlsVersion = handshake?.tlsVersion?.toString(),
                cipherSuite = handshake?.cipherSuite?.toString(),
            ),
        )
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        trace.setPhase("http_exchange")
        trace.addNetworkEvent(
            SpeedTestNetworkEvent(
                event = "connect_end",
                phase = "http_exchange",
                host = call.request().url.host,
                proxy = proxy.safeDescription(),
                socketAddress = inetSocketAddress.safeDescription(),
                protocol = protocol?.toString(),
            ),
        )
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        trace.addNetworkEvent(
            SpeedTestNetworkEvent(
                event = "connect_failed",
                phase = trace.currentPhase(),
                host = call.request().url.host,
                proxy = proxy.safeDescription(),
                socketAddress = inetSocketAddress.safeDescription(),
                protocol = protocol?.toString(),
                exceptionClass = ioe.javaClass.name,
                causeClass = ioe.cause?.javaClass?.name,
            ),
        )
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        val route = connection.route()
        val handshake = connection.handshake()
        trace.setPhase("http_exchange")
        trace.addNetworkEvent(
            SpeedTestNetworkEvent(
                event = "connection_acquired",
                phase = "http_exchange",
                host = call.request().url.host,
                proxy = route.proxy.safeDescription(),
                socketAddress = route.socketAddress.safeDescription(),
                protocol = connection.protocol().toString(),
                tlsVersion = handshake?.tlsVersion?.toString(),
                cipherSuite = handshake?.cipherSuite?.toString(),
            ),
        )
    }

    override fun callFailed(call: Call, ioe: IOException) {
        trace.addNetworkEvent(
            SpeedTestNetworkEvent(
                event = "call_failed",
                phase = trace.currentPhase(),
                host = call.request().url.host,
                exceptionClass = ioe.javaClass.name,
                causeClass = ioe.cause?.javaClass?.name,
            ),
        )
    }

    companion object : EventListener.Factory {
        override fun create(call: Call): EventListener =
            call.request().tag(ProbeAttemptTrace::class.java)?.let(::ProbeEventListener) ?: EventListener.NONE
    }
}

private fun Proxy.safeDescription(): String {
    if (type() == Proxy.Type.DIRECT) return "DIRECT"
    val endpoint = when (val value = address()) {
        is InetSocketAddress -> "${value.hostString}:${value.port}"
        null -> "unknown"
        else -> value.javaClass.simpleName
    }
    return "${type().name}:$endpoint"
}

private fun InetSocketAddress.safeDescription(): String {
    val host = address?.hostAddress?.substringBefore('%') ?: hostString
    return "$host:$port"
}
