package com.zycomic.app.net

import android.content.MemoryContext
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

object PicacgRouteManager {
    data class State(val coverRanking: List<DomainIp>, val contentRanking: List<DomainIp>)
    val state: MutableStateFlow<State> get() = MutableStateFlow(State(coverRankings.toList(), contentRankings.toList()))

    @Volatile var configRevision: Long = 1L
        private set

    @Volatile var remoteVersion: Int? = 1
        private set

    @Volatile var apiUrls: List<String> = emptyList()
        private set

    @Volatile var imageHosts: List<String> = emptyList()
        private set

    @Volatile private var selectedApiUrl: String? = null

    @Volatile var verifiedImageSampleUrl: String? = null
        private set

    @Volatile var isApiManuallyPinned: Boolean = false

    @Volatile var isImageManuallyPinned: Boolean = false

    @Volatile var selectedApiEndpoint: DomainIp? = null
        private set

    @Volatile var selectedImageEndpoint: DomainIp? = null
        private set

    val selectedApiHost: String?
        get() = selectedApiUrl?.let { URI(it).host } ?: apiUrls.firstOrNull()?.let { URI(it).host }

    fun imageSourceFor(content: Boolean): String? {
        if (isImageManuallyPinned) return selectedImageEndpoint?.domain ?: imageHosts.firstOrNull()
        val ranking = if (content) contentRankings else coverRankings
        return ranking.firstOrNull()?.domain ?: imageHosts.firstOrNull()
    }

    private var ips: Map<String, List<String>> = emptyMap()
    private var responseCodes: Map<String, Int> = emptyMap()
    private var coverCodes: Map<String, Int> = emptyMap()
    private var contentCodes: Map<String, Int> = emptyMap()
    private val requests = CopyOnWriteArrayList<String>()
    private val imagePaths = CopyOnWriteArrayList<String>()
    private val coverRankings = CopyOnWriteArrayList<DomainIp>()
    private val contentRankings = CopyOnWriteArrayList<DomainIp>()
    private val resolutions = CopyOnWriteArrayList<String>()
    private val apiSelections = CopyOnWriteArrayList<String>()
    val runtimeTestRequests: List<String> get() = requests.toList()
    val runtimeTestImagePaths: List<String> get() = imagePaths.toList()
    val runtimeTestCoverRanking: List<DomainIp> get() = coverRankings.toList()
    val runtimeTestContentRanking: List<DomainIp> get() = contentRankings.toList()
    val runtimeTestResolutions: List<String> get() = resolutions.toList()
    val runtimeTestApiSelections: List<String> get() = apiSelections.toList()

    @Synchronized fun resetForRuntimeTest() {
        DevConfig.appContext = MemoryContext()
        apiUrls = emptyList()
        imageHosts = emptyList()
        selectedApiUrl = null
        ips = emptyMap()
        responseCodes = emptyMap()
        coverCodes = emptyMap()
        contentCodes = emptyMap()
        requests.clear()
        imagePaths.clear()
        coverRankings.clear()
        contentRankings.clear()
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
        selectedApiUrl = null
        ips = routeIps.mapKeys { it.key.lowercase() }
        remoteVersion = version
        configRevision++
        requests.clear()
        imagePaths.clear()
        resolutions.clear()
        apiSelections.clear()
        selectedApiEndpoint = null
        selectedImageEndpoint = null
    }

    fun setRuntimeTestResponses(codes: Map<String, Int>) {
        responseCodes = codes.mapKeys { it.key.lowercase() }
    }
    fun setRuntimeTestImageResponses(covers: Map<String, Int>, content: Map<String, Int>) {
        coverCodes = covers.mapKeys { it.key.lowercase() }
        contentCodes = content.mapKeys { it.key.lowercase() }
    }
    fun clearRuntimeTestTraffic() {
        requests.clear()
        imagePaths.clear()
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
        selectedApiUrl = url
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
    fun clearAutomaticHealthSelections() {
        if (!isApiManuallyPinned) selectedApiEndpoint = null
        if (!isImageManuallyPinned) {
            selectedImageEndpoint = null
            coverRankings.clear()
            contentRankings.clear()
        }
    }
    fun applyImageRankings(cover: List<DomainIp>, content: List<DomainIp>): Boolean {
        if (isImageManuallyPinned) return false
        coverRankings.clear()
        coverRankings.addAll(cover)
        contentRankings.clear()
        contentRankings.addAll(content)
        return true
    }
    fun newDiagnosticPinnedClient(base: OkHttpClient, endpoint: DomainIp): OkHttpClient = newPinnedClient(base, endpoint)
    fun newPinnedClient(base: OkHttpClient, endpoint: DomainIp): OkHttpClient = base.newBuilder().addInterceptor(
        Interceptor { chain ->
            val request = chain.request()
            val host = request.url.host.lowercase()
            requests += host
            val content = request.url.encodedPath.startsWith("/static/")
            val image = request.url.encodedPath.startsWith("/insecure/") || content
            val code = if (content) contentCodes[host] ?: 200 else if (image) coverCodes[host] ?: 200 else responseCodes[host] ?: 200
            if (image) imagePaths += "${request.url.host}${request.url.encodedPath}"
            val body = if (image && code == 200) byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0) else byteArrayOf()
            val builder = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("synthetic")
                .body(ResponseBody.create(null, body))
            if (image && code == 200) builder.header("Content-Type", "image/jpeg")
            builder.build()
        },
    ).build()
}
