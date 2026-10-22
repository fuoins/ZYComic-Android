package com.zycomic.app.net

import android.content.MemoryContext
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

object PicacgRouteManager {
    @Volatile var configRevision: Long = 1L
        private set

    @Volatile var remoteVersion: Int? = 1
        private set

    @Volatile var apiUrls: List<String> = emptyList()
        private set

    @Volatile var imageHosts: List<String> = emptyList()
        private set

    @Volatile var verifiedImageSampleUrl: String? = null
        private set

    @Volatile var isApiManuallyPinned: Boolean = false

    @Volatile var isImageManuallyPinned: Boolean = false

    @Volatile var selectedApiEndpoint: DomainIp? = null
        private set

    @Volatile var selectedImageEndpoint: DomainIp? = null
        private set

    private var ips: Map<String, List<String>> = emptyMap()
    private var responseCodes: Map<String, Int> = emptyMap()
    private val requests = CopyOnWriteArrayList<String>()
    private val resolutions = CopyOnWriteArrayList<String>()
    private val apiSelections = CopyOnWriteArrayList<String>()
    val runtimeTestRequests: List<String> get() = requests.toList()
    val runtimeTestResolutions: List<String> get() = resolutions.toList()
    val runtimeTestApiSelections: List<String> get() = apiSelections.toList()

    @Synchronized fun resetForRuntimeTest() {
        DevConfig.appContext = MemoryContext()
        apiUrls = emptyList()
        imageHosts = emptyList()
        ips = emptyMap()
        responseCodes = emptyMap()
        requests.clear()
        resolutions.clear()
        apiSelections.clear()
        remoteVersion = 1
        configRevision++
        verifiedImageSampleUrl = null
        selectedApiEndpoint = null
        selectedImageEndpoint = null
        isApiManuallyPinned = false
        isImageManuallyPinned = false
    }

    @Synchronized fun configureForRuntimeTest(urls: List<String>, images: List<String>, routeIps: Map<String, List<String>>, version: Int?) {
        apiUrls = urls.toList()
        imageHosts = images.toList()
        ips = routeIps.mapKeys { it.key.lowercase() }
        remoteVersion = version
        configRevision++
        requests.clear()
        resolutions.clear()
        apiSelections.clear()
        selectedApiEndpoint = null
        selectedImageEndpoint = null
    }

    fun setRuntimeTestResponses(codes: Map<String, Int>) {
        responseCodes = codes.mapKeys { it.key.lowercase() }
    }
    fun clearRuntimeTestTraffic() {
        requests.clear()
        resolutions.clear()
        apiSelections.clear()
    }
    fun invalidateRuntimeTestRevision() {
        configRevision++
    }
    fun resolveIps(host: String): List<String> {
        resolutions += host
        return ips[host.lowercase()].orEmpty()
    }
    fun apiRouteRules(): Map<String, List<String>> = ips.filterKeys { key -> apiUrls.any { URI(it).host.equals(key, true) } }
    fun imageRouteRules(): Map<String, List<String>> = ips.filterKeys { it in imageHosts }
    fun dns(): Dns = Dns.SYSTEM
    fun selectApiUrl(url: String, manual: Boolean = true): Boolean {
        apiSelections += URI(url).host
        return true
    }
    fun selectImageHost(host: String, manual: Boolean = true): Boolean = true
    fun applyAutomaticApiEndpoint(endpoint: DomainIp?): Boolean {
        selectedApiEndpoint = endpoint
        return true
    }
    fun applyAutomaticImageEndpoint(endpoint: DomainIp?): Boolean {
        selectedImageEndpoint = endpoint
        return true
    }
    fun applyImageRankings(cover: List<DomainIp>, content: List<DomainIp>): Boolean = false
    fun newDiagnosticPinnedClient(base: OkHttpClient, endpoint: DomainIp): OkHttpClient = newPinnedClient(base, endpoint)
    fun newPinnedClient(base: OkHttpClient, endpoint: DomainIp): OkHttpClient = base.newBuilder().addInterceptor(
        Interceptor { chain ->
            val request = chain.request()
            val host = request.url.host.lowercase()
            requests += host
            val code = responseCodes[host] ?: 200
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("synthetic")
                .body(ResponseBody.create(null, byteArrayOf()))
                .build()
        },
    ).build()
}
