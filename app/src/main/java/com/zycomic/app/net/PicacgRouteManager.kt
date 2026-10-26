package com.zycomic.app.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Picacg-only runtime routes. This manager never reads or mutates Manwa/JM route preferences. */
object PicacgRouteManager {
    data class State(
        val apiUrls: List<String> = emptyList(),
        val imageHosts: List<String> = emptyList(),
        val apiIndex: Int = 0,
        val imageHost: String? = null,
        val pinnedApi: DomainIp? = null,
        val pinnedImage: DomainIp? = null,
        /**
         * Best endpoint per content root (`storage-b.{root}`) measured for READER PAGES (the
         * storage-b bucket), ordered fastest-first. Keys are storage-b hosts; the cover mirror
         * `img.{root}` shares the same root but is ranked independently in [coverRanking].
         */
        val contentRanking: List<DomainIp> = emptyList(),
        /** Best endpoint per content root measured for COVERS (imgproxy), ordered fastest-first. */
        val coverRanking: List<DomainIp> = emptyList(),
        val remoteVersion: Int? = null,
        val revision: Long = 0L,
        val manualApi: Boolean = false,
        val manualImage: Boolean = false,
    )

    internal data class ApiRecoveryCandidate(
        val url: String,
        val endpoint: DomainIp,
        val snapshot: PicacgSpeedTester.Snapshot,
    )

    private const val PREFS_NAME = "zycomic_picacg_route"
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var routeConfigRevision: Long = 0L

    /** Current configured API base URL, normalized for Retrofit and without any local domain fallback. */
    val apiBaseUrl: String get() = currentBaseUrl()
    /** Compatibility name consumed by PicacgClient. */
    val baseUrl: String get() = currentBaseUrl()
    fun currentBaseUrl(): String = _state.value.apiUrls.getOrNull(_state.value.apiIndex)?.let(::withTrailingSlash).orEmpty()
    val currentImageHost: String? get() = _state.value.imageHost
    /** The active source is independent for covers and reader pages unless the user pinned a root. */
    fun imageSourceFor(content: Boolean): String? {
        val current = _state.value
        if (isImageManuallyPinned) return current.imageHost
        val ranking = if (content) current.contentRanking else current.coverRanking
        return ranking.firstOrNull()?.domain ?: current.imageHost
    }
    /** Compatibility name consumed by PicacgRepository. */
    val imageHost: String? get() = currentImageHost
    val apiUrls: List<String> get() = _state.value.apiUrls
    val imageHosts: List<String> get() = _state.value.imageHosts
    val selectedApiHost: String? get() = _state.value.apiUrls.getOrNull(_state.value.apiIndex)?.let(::hostOf)
    val selectedApiEndpoint: DomainIp? get() = _state.value.pinnedApi
    val selectedImageEndpoint: DomainIp? get() = _state.value.pinnedImage
    val remoteVersion: Int? get() = _state.value.remoteVersion
    /** Changes only when authoritative route pools/rules are installed or cleared, not on selection. */
    val configRevision: Long get() = routeConfigRevision
    val isApiManuallyPinned: Boolean get() = _state.value.manualApi || prefs()?.getBoolean("manual_api_selection", false) == true
    val isImageManuallyPinned: Boolean get() = _state.value.manualImage || prefs()?.getBoolean("manual_image_selection", false) == true

    private fun prefs() = DevConfig.appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Client-owned pools are independent of optional lines3/sources3 in remote snapshots. */
    @Synchronized
    internal fun installBuiltin(version: Int?, smartIps: List<String> = emptyList()) {
        val rules = PicacgBuiltinNetwork.rules(smartIps)
        applyRemoteConfig(PicacgBuiltinNetwork.apiUrls, PicacgBuiltinNetwork.imageHosts, rules, version)
        dnsFallbackRules = rules
    }

    /** Install only already-validated remote values; no built-in domain, IP, or fallback pool is introduced. */
    @Synchronized
    fun applyRemoteConfig(apiUrls: List<String>, imageHosts: List<String>, rules: Map<String, List<String>>, version: Int?) {
        val normalizedUrls = apiUrls.map(String::trim).filter(PicacgNetworkPolicy::apiBaseAllowed)
            .distinctBy { hostOf(it).lowercase(Locale.ROOT) }
        val normalizedHosts = imageHosts.map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotEmpty).distinct()
        val old = _state.value
        val prefs = prefs()
        val savedApi = prefs?.getString("selected_api_host", null)
        val savedImage = prefs?.getString("selected_image_host", null)
        val manualApi = prefs?.getBoolean("manual_api_selection", false) == true &&
            savedApi != null && normalizedUrls.any { hostOf(it).equals(savedApi, true) }
        val manualImage = prefs?.getBoolean("manual_image_selection", false) == true &&
            savedImage != null && normalizedHosts.any { it.equals(savedImage, true) }
        val apiIndex = if (manualApi) {
            normalizedUrls.indexOfFirst { hostOf(it).equals(savedApi, true) }.coerceAtLeast(0)
        } else {
            normalizedUrls.indexOfFirst { hostOf(it).equals(old.apiUrls.getOrNull(old.apiIndex)?.let(::hostOf), true) }.takeIf { it >= 0 } ?: 0
        }
        val imageHost = (if (manualImage) savedImage else null)?.let { saved -> normalizedHosts.firstOrNull { it.equals(saved, true) } }
            ?: old.imageHost?.let { previous -> normalizedHosts.firstOrNull { it.equals(previous, true) } }
            ?: normalizedHosts.firstOrNull()
        val apiRuleHosts = normalizedUrls.map(::hostOf).map { it.lowercase(Locale.ROOT) }.toSet()
        val imageRuleHosts = normalizedHosts.toSet()
        val apiRules = rules.filterKeys { it.lowercase(Locale.ROOT) in apiRuleHosts }.mapKeys { it.key.lowercase(Locale.ROOT) }
        val imageRules = rules.filterKeys { it.lowercase(Locale.ROOT) in imageRuleHosts }.mapKeys { it.key.lowercase(Locale.ROOT) }
        val oldApiPin = old.pinnedApi ?: persistedEndpoint("api_pin_domain", "api_pin_ip")
        val oldImagePin = old.pinnedImage ?: persistedEndpoint("image_pin_domain", "image_pin_ip")
        val apiPin = oldApiPin?.takeIf { endpoint ->
            apiRules.entries.firstOrNull { it.key.equals(endpoint.domain, true) }?.value?.contains(endpoint.ip) == true
        }
        val imagePin = oldImagePin?.takeIf { endpoint ->
            imageRules.entries.firstOrNull { it.key.equals(endpoint.domain, true) }?.value?.contains(endpoint.ip) == true
        }
        val next = State(
            apiUrls = normalizedUrls,
            imageHosts = normalizedHosts,
            apiIndex = apiIndex,
            imageHost = imageHost,
            pinnedApi = apiPin,
            pinnedImage = imagePin,
            remoteVersion = version,
            revision = old.revision + 1,
            manualApi = manualApi,
            manualImage = manualImage,
        )
        // Publish a coherent rules/configuration snapshot before waking state observers.
        apiRouteRules = apiRules
        imageRouteRules = imageRules
        dnsFallbackRules = emptyMap()
        routeConfigRevision++
        _state.value = next
        prefs?.edit()?.apply {
            putString("configured_api_urls", JSONArray(normalizedUrls).toString())
            putString("configured_image_hosts", JSONArray(normalizedHosts).toString())
            putInt("remote_version", version ?: -1)
            if (manualApi && normalizedUrls.isNotEmpty()) {
                putString("selected_api_host", hostOf(normalizedUrls[apiIndex]))
            } else if (!manualApi) {
                putBoolean("manual_api_selection", false)
                remove("selected_api_host")
            }
            if (manualImage && imageHost != null) {
                putString("selected_image_host", imageHost)
            } else if (!manualImage) {
                putBoolean("manual_image_selection", false)
                remove("selected_image_host")
            }
            persistEndpoint("api_pin", apiPin)
            persistEndpoint("image_pin", imagePin)
        }?.apply()
    }

    @Volatile private var apiRouteRules: Map<String, List<String>> = emptyMap()

    @Volatile private var imageRouteRules: Map<String, List<String>> = emptyMap()

    @Volatile private var dnsFallbackRules: Map<String, List<String>> = emptyMap()

    /** Restore saved selection/pins only after the validated authoritative route pools have been installed. */
    @Synchronized
    fun loadPreferences() {
        val prefs = prefs() ?: return
        val old = _state.value
        if (old.apiUrls.isEmpty() && old.imageHosts.isEmpty()) return
        val savedApiHost = prefs.getString("selected_api_host", null)
        val index = if (prefs.getBoolean("manual_api_selection", false) && savedApiHost != null) {
            old.apiUrls.indexOfFirst { hostOf(it).equals(savedApiHost, true) }.coerceAtLeast(0)
        } else {
            old.apiIndex
        }
        val imageHost = prefs.getString("selected_image_host", null)?.let { saved -> old.imageHosts.firstOrNull { it.equals(saved, true) } }
            ?: old.imageHost ?: old.imageHosts.firstOrNull()
        val restoredCover = persistedRanking("cover_ranking").filter { ep -> old.imageHosts.any { it.equals(ep.domain, true) } }
        val restoredContent = persistedRanking("content_ranking").filter { ep -> old.imageHosts.any { it.equals(ep.domain, true) } }
        _state.value = old.copy(
            apiIndex = index,
            imageHost = imageHost,
            pinnedApi = persistedEndpoint("api_pin_domain", "api_pin_ip"),
            pinnedImage = persistedEndpoint("image_pin_domain", "image_pin_ip"),
            coverRanking = restoredCover,
            contentRanking = restoredContent,
            revision = old.revision + 1,
            manualApi = prefs.getBoolean("manual_api_selection", false),
            manualImage = prefs.getBoolean("manual_image_selection", false),
        )
    }

    /** Set a candidate API base URL; candidate must be in the active remote pool. */
    @Synchronized
    fun selectApiUrl(url: String, manual: Boolean = true): Boolean {
        val state = _state.value
        val index = state.apiUrls.indexOfFirst { hostOf(it).equals(hostOf(url), true) }
        if (index < 0) return false
        prefs()?.edit()?.putBoolean("manual_api_selection", manual)
            ?.apply { if (manual) putString("selected_api_host", hostOf(state.apiUrls[index])) else remove("selected_api_host") }?.apply()
        _state.value = state.copy(apiIndex = index, revision = state.revision + 1, manualApi = manual)
        return true
    }

    /** Ordered configured API alternates; never overrides a user's fixed host selection. */
    internal fun apiRecoveryCandidates(expectedHost: String): List<ApiRecoveryCandidate> {
        if (isApiManuallyPinned || !RuntimeNetworkGate.isOnline()) return emptyList()
        val current = _state.value
        val index = current.apiUrls.indexOfFirst { hostOf(it).equals(expectedHost, true) }
        if (index < 0 || index != current.apiIndex) return emptyList()
        val snapshot = PicacgSpeedTester.snapshot.value ?: return emptyList()
        if (snapshot.revision != configRevision) return emptyList()
        val committedEndpoint = current.pinnedApi?.takeIf { it.domain.equals(expectedHost, true) } ?: return emptyList()
        return PicacgApiCandidatePolicy.candidates(
            apiUrls = current.apiUrls,
            currentIndex = index,
            expectedHost = expectedHost,
            committedEndpoint = committedEndpoint,
            probes = snapshot.apiProbes.map { PicacgApiProbeEndpoint(it.endpoint, it.success, it.elapsedMs) },
            snapshotRevision = snapshot.revision,
            configRevision = configRevision,
            apiRouteRules = apiRouteRules,
            manual = isApiManuallyPinned,
            online = RuntimeNetworkGate.isOnline(),
        ).map { candidate ->
            ApiRecoveryCandidate(candidate.url, candidate.endpoint, snapshot)
        }
    }

    /** Commit only a configured alternate already confirmed by a safe API probe. */
    @Synchronized
    internal fun advanceRuntimeApiAfterRecovery(
        expectedHost: String,
        expectedEndpoint: DomainIp,
        expectedConfigRevision: Long,
        expectedNetworkRevision: Long,
        expectedPlatformRevision: Long,
        validatedCandidate: ApiRecoveryCandidate,
    ): Boolean {
        if (!RuntimeNetworkGate.isOnline() || RuntimeNetworkGate.revision != expectedNetworkRevision ||
            configRevision != expectedConfigRevision || isApiManuallyPinned ||
            !com.zycomic.app.platform.StartupPlatformPolicy.isEnabled(com.zycomic.app.platform.ContentPlatform.PICACG) ||
            com.zycomic.app.platform.StartupPlatformPolicy.state.value.revision != expectedPlatformRevision
        ) {
            return false
        }
        val current = _state.value
        val activeUrl = current.apiUrls.getOrNull(current.apiIndex).orEmpty()
        if (!hostOf(activeUrl).equals(expectedHost, true) ||
            current.pinnedApi?.normalizedForComparison() != expectedEndpoint.normalizedForComparison() ||
            PicacgSpeedTester.snapshot.value != validatedCandidate.snapshot ||
            validatedCandidate.snapshot.revision != expectedConfigRevision ||
            validatedCandidate !in apiRecoveryCandidates(expectedHost)
        ) {
            return false
        }
        val candidateIndex = current.apiUrls.indexOfFirst { it.equals(validatedCandidate.url, true) }
        if (candidateIndex < 0 || candidateIndex == current.apiIndex) return false
        _state.value = current.copy(
            apiIndex = candidateIndex,
            pinnedApi = validatedCandidate.endpoint,
            revision = current.revision + 1,
        )
        return true
    }

    /** Returns only the endpoint currently committed to the active API host. */
    fun currentApiEndpointFor(host: String): DomainIp? = _state.value.let { current ->
        current.pinnedApi?.takeIf { it.domain.equals(host, true) && hostOf(current.apiUrls.getOrNull(current.apiIndex).orEmpty()).equals(host, true) }
    }

    /** Read-only health probe reusing the same exact-IP diagnostic path as the existing speed test. */
    suspend fun validateCurrentApi(): Boolean {
        if (!RuntimeNetworkGate.isOnline()) return false
        val url = currentBaseUrl()
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        val endpoint = currentApiEndpointFor(host) ?: return false
        val networkRevision = RuntimeNetworkGate.revision
        val valid = PicacgSpeedTester.validateRuntimeApiEndpoint(endpoint, url)
        return valid && RuntimeNetworkGate.isOnline() && RuntimeNetworkGate.revision == networkRevision
    }

    @Synchronized
    fun selectImageHost(host: String, manual: Boolean = true): Boolean {
        val candidate = _state.value.imageHosts.firstOrNull { it.equals(host.trim(), true) } ?: return false
        prefs()?.edit()?.putBoolean("manual_image_selection", manual)
            ?.apply { if (manual) putString("selected_image_host", candidate) else remove("selected_image_host") }?.apply()
        _state.value = _state.value.copy(imageHost = candidate, revision = _state.value.revision + 1, manualImage = manual)
        return true
    }

    /** Previously tested candidates only; configured host ordering stays unchanged. */
    internal fun runtimeImageRecoveryCandidates(
        expectedSource: String,
        service: PicacgImageService? = null,
    ): List<DomainIp> {
        if (isImageManuallyPinned) return emptyList()
        val snapshot = PicacgSpeedTester.snapshot.value ?: return emptyList()
        if (snapshot.revision != configRevision) return emptyList()
        val index = imageHosts.indexOfFirst { it.equals(expectedSource, true) }
        if (index < 0) return emptyList()
        val pool = if (service == PicacgImageService.BODY) {
            snapshot.imageProbes.filter { it.content }
        } else if (service == PicacgImageService.COVER) {
            snapshot.imageProbes.filterNot { it.content }
        } else {
            snapshot.imageProbes
        }
        return imageHosts.filterNot { it.equals(expectedSource, true) }.mapNotNull { host ->
            pool.filter { it.success && it.endpoint.domain.equals(host, true) }
                .minByOrNull { it.elapsedMs }?.endpoint
        }
    }

    @Synchronized
    internal fun advanceRuntimeImageSourceAfterRecovery(
        expectedSource: String,
        expectedConfigRevision: Long,
        expectedPlatformRevision: Long,
        validatedEndpoint: DomainIp,
        service: PicacgImageService? = null,
    ): Boolean {
        if (!RuntimeNetworkGate.isOnline() || configRevision != expectedConfigRevision ||
            !com.zycomic.app.platform.StartupPlatformPolicy.isEnabled(com.zycomic.app.platform.ContentPlatform.PICACG) ||
            com.zycomic.app.platform.StartupPlatformPolicy.state.value.revision != expectedPlatformRevision ||
            !imageSourceFor(service == PicacgImageService.BODY).equals(expectedSource, true) || isImageManuallyPinned ||
            validatedEndpoint !in runtimeImageRecoveryCandidates(expectedSource, service)
        ) {
            return false
        }
        if (service != null) {
            val current = _state.value
            val oldRanking = if (service == PicacgImageService.BODY) current.contentRanking else current.coverRanking
            val newRanking = (listOf(validatedEndpoint) + oldRanking.filterNot { it.domain.equals(validatedEndpoint.domain, true) })
            prefs()?.edit()?.putString(
                if (service == PicacgImageService.BODY) "content_ranking" else "cover_ranking",
                encodeRanking(newRanking),
            )?.apply()
            _state.value = if (service == PicacgImageService.BODY) {
                current.copy(contentRanking = newRanking, revision = current.revision + 1)
            } else {
                current.copy(coverRanking = newRanking, revision = current.revision + 1)
            }
            return true
        }
        if (!applyAutomaticImageEndpoint(validatedEndpoint)) return false
        return selectImageHost(validatedEndpoint.domain, manual = false)
    }

    /** Pin an API request to a host/IP pair obtained from the configured rules or measured DNS. */
    @Synchronized
    fun pinApiEndpoint(endpoint: DomainIp?): Boolean = setPin(endpoint, true)

    /** Pin image requests independently from API traffic. */
    @Synchronized
    fun pinImageEndpoint(endpoint: DomainIp?): Boolean = setPin(endpoint, false)

    /** Apply an already measured API host/IP pair atomically without racing a manual selection. */
    @Synchronized
    internal fun applyAutomaticApiRoute(url: String, endpoint: DomainIp): Boolean {
        if (isApiManuallyPinned) return false
        val current = _state.value
        val index = current.apiUrls.indexOfFirst { hostOf(it).equals(hostOf(url), true) }
        if (index < 0 || !hostOf(current.apiUrls[index]).equals(endpoint.domain, true) ||
            !isIpv4Literal(endpoint.ip) ||
            apiRouteRules.entries.firstOrNull { it.key.equals(endpoint.domain, true) }?.value?.contains(endpoint.ip) != true
        ) {
            return false
        }
        prefs()?.edit()?.apply {
            putBoolean("manual_api_selection", false)
            remove("selected_api_host")
            persistEndpoint("api_pin", endpoint)
        }?.apply()
        _state.value = current.copy(apiIndex = index, pinnedApi = endpoint, revision = current.revision + 1)
        return true
    }
    /** Apply a measured endpoint without changing the user's manual-selection preference. */
    fun applyAutomaticApiEndpoint(endpoint: DomainIp?): Boolean = setPin(endpoint, true, manual = false)

    /** Apply a measured image endpoint without changing the user's manual-selection preference. */
    fun applyAutomaticImageEndpoint(endpoint: DomainIp?): Boolean = setPin(endpoint, false, manual = false)

    /** Clear automatic health-derived choices before a new speed-test round; keep user pins intact. */
    @Synchronized
    internal fun clearAutomaticHealthSelections() {
        val current = _state.value
        val clearApi = !isApiManuallyPinned
        val clearImage = !isImageManuallyPinned
        if (!clearApi && !clearImage) return
        prefs()?.edit()?.apply {
            if (clearApi) persistEndpoint("api_pin", null)
            if (clearImage) {
                persistEndpoint("image_pin", null)
                putString("cover_ranking", encodeRanking(emptyList()))
                putString("content_ranking", encodeRanking(emptyList()))
            }
        }?.apply()
        _state.value = current.copy(
            pinnedApi = if (clearApi) null else current.pinnedApi,
            pinnedImage = if (clearImage) null else current.pinnedImage,
            coverRanking = if (clearImage) emptyList() else current.coverRanking,
            contentRanking = if (clearImage) emptyList() else current.contentRanking,
            imageHost = if (clearImage) current.imageHosts.firstOrNull() else current.imageHost,
            revision = current.revision + 1,
        )
    }

    /**
     * Install the two independent, per-root rankings: one best IP per `storage-b.{root}` for covers
     * (imgproxy) and one for reader pages (the storage-b bucket), each ordered fastest-first by its
     * own probe. The first CONTENT entry becomes the active selection/pin; every later entry in both
     * lists remains an automatic fallback used by [candidateImageUrls]. Entries must already exist in
     * the active image route rules; invalid/out-of-pool endpoints are dropped.
     */
    @Synchronized
    fun applyImageRankings(cover: List<DomainIp>, content: List<DomainIp>): Boolean {
        val current = _state.value
        fun valid(ranking: List<DomainIp>): List<DomainIp> = ranking
            .filter { ep ->
                isIpv4Literal(ep.ip) &&
                    current.imageHosts.any { it.equals(ep.domain, true) } &&
                    imageRouteRules.entries.firstOrNull { it.key.equals(ep.domain, true) }?.value?.contains(ep.ip) == true
            }
            .distinctBy { it.domain.lowercase(Locale.ROOT) }
        val validCover = valid(cover)
        val validContent = valid(content)
        if (isImageManuallyPinned) return false
        // Empty results are authoritative too: they must remove stale automatic health rankings.
        // Only a successful reader-page probe may establish the content bucket pin.
        val firstContent = validContent.firstOrNull()
        val imageHost = firstContent?.domain ?: current.imageHost?.takeIf { host -> current.imageHosts.any { it.equals(host, true) } }
            ?: current.imageHosts.firstOrNull()
        prefs()?.edit()?.apply {
            putString("cover_ranking", encodeRanking(validCover))
            putString("content_ranking", encodeRanking(validContent))
            persistEndpoint("image_pin", firstContent)
        }?.apply()
        _state.value = current.copy(
            coverRanking = validCover,
            contentRanking = validContent,
            imageHost = imageHost,
            pinnedImage = firstContent,
            revision = current.revision + 1,
        )
        return true
    }

    private fun encodeRanking(ranking: List<DomainIp>): String =
        JSONArray().apply {
            ranking.forEach { ep -> put(JSONObject().put("domain", ep.domain).put("ip", ep.ip)) }
        }.toString()

    /** Ordered content hosts (`storage-b.{root}`) for the given service, then any unmeasured pool host. */
    fun rankedContentHosts(forContent: Boolean): List<String> {
        val state = _state.value
        val ranking = if (forContent) state.contentRanking else state.coverRanking
        val ranked = ranking.map { it.domain }.filter { d -> state.imageHosts.any { it.equals(d, true) } }
        val manual = state.imageHost?.takeIf { host -> isImageManuallyPinned && ranked.none { it.equals(host, true) } }
        val rest = state.imageHosts.filter { h ->
            h != manual && ranked.none { it.equals(h, true) }
        }
        return (listOfNotNull(manual) + ranked + rest).distinctBy { it.lowercase(Locale.ROOT) }
    }

    /** Best measured IP for a host: storage-b uses the content ranking, img.{root} uses the cover ranking. */
    private fun rankedIpFor(hostname: String?): String? {
        if (hostname == null) return null
        val state = _state.value
        // Storage-b/body traffic is pinned only by a reader-page measurement, never a cover result.
        if (state.imageHosts.any { it.equals(hostname, true) }) {
            return state.contentRanking.firstOrNull { it.domain.equals(hostname, true) }?.ip
        }
        // The corresponding imgproxy host is independently pinned by its cover measurement.
        val contentHost = PicacgBuiltinNetwork.contentHostFor(hostname) ?: return null
        return state.coverRanking.firstOrNull { it.domain.equals(contentHost, true) }?.ip
    }

    private fun persistedRanking(key: String): List<DomainIp> {
        val p = prefs() ?: return emptyList()
        val raw = p.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val domain = obj.optString("domain").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val ip = obj.optString("ip").takeIf { isIpv4Literal(it) } ?: return@mapNotNull null
                DomainIp(domain, ip)
            }
        }.getOrDefault(emptyList())
    }

    private fun setPin(endpoint: DomainIp?, api: Boolean, manual: Boolean = true): Boolean {
        val current = _state.value
        val allowedHosts = if (api) current.apiUrls.map(::hostOf) else current.imageHosts
        val allowedIps = if (api) apiRouteRules else imageRouteRules
        if (endpoint != null && (
                allowedHosts.none { it.equals(endpoint.domain, true) } || !isIpv4Literal(endpoint.ip) ||
                    allowedIps.entries.firstOrNull { it.key.equals(endpoint.domain, true) }?.value?.contains(endpoint.ip) != true
                )
        ) {
            return false
        }
        val prefs = prefs()
        if (api) {
            prefs?.edit()?.apply {
                putBoolean("manual_api_selection", manual)
                putString("api_pin_domain", endpoint?.domain)
                putString("api_pin_ip", endpoint?.ip)
            }?.apply()
            _state.value = current.copy(pinnedApi = endpoint, revision = current.revision + 1, manualApi = manual)
        } else {
            prefs?.edit()?.apply {
                putBoolean("manual_image_selection", manual)
                putString("image_pin_domain", endpoint?.domain)
                putString("image_pin_ip", endpoint?.ip)
            }?.apply()
            _state.value = current.copy(pinnedImage = endpoint, revision = current.revision + 1, manualImage = manual)
        }
        return true
    }

    fun routeRules(): Map<String, List<String>> = apiRouteRules + imageRouteRules
    fun apiRouteRules(): Map<String, List<String>> = apiRouteRules.toMap()
    fun imageRouteRules(): Map<String, List<String>> = imageRouteRules.toMap()
    fun resolveIps(host: String): List<String> =
        PicacgBuiltinNetwork.lookup(dnsFallbackRules + apiRouteRules + imageRouteRules, host)
            .ifEmpty { PicacgBuiltinNetwork.builtInLookup(host) }

    /**
     * DNS policy for Picacg clients; applies only Picacg's remote rules and endpoint pins.
     * A cover mirror (`img.{root}`) shares the pinned content bucket (`storage-b.{root}`) IP because
     * the two are same-root anycast mirrors, so the cover request follows the measured fastest IP too.
     */
    fun dns(): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // Per-root measured IP first: content bucket (storage-b.{root}) or its cover mirror (img.{root}).
            val imagePin = rankedIpFor(hostname)
            val pin = imagePin
                ?: _state.value.pinnedApi?.takeIf { it.domain.equals(hostname, true) }?.ip
                ?: _state.value.pinnedImage?.takeIf { it.domain.equals(hostname, true) }?.ip
            val addresses = pin?.let(::listOf) ?: resolveIps(hostname)
            if (addresses.isNotEmpty()) return addresses.map(InetAddress::getByName)
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    /** Create an isolated, connection-pool-free client pinned to exactly one measured endpoint. */
    fun newPinnedClient(base: OkHttpClient, endpoint: DomainIp): OkHttpClient {
        require(isIpv4Literal(endpoint.ip)) { "Invalid Picacg endpoint address" }
        require((apiUrls.map(::hostOf) + imageHosts).any { it.equals(endpoint.domain, true) }) { "Host is not in the active Picacg route pools" }
        return base.newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = if (hostname.equals(endpoint.domain, true)) {
                    listOf(InetAddress.getByName(endpoint.ip))
                } else {
                    dns().lookup(hostname)
                }
            })
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
            .retryOnConnectionFailure(false)
            .build()
    }

    /** Diagnostic child client: only the measured host is resolvable and redirects cannot change the target. */
    fun newDiagnosticPinnedClient(base: OkHttpClient, endpoint: DomainIp): OkHttpClient {
        require(isIpv4Literal(endpoint.ip)) { "Invalid Picacg endpoint address" }
        require((apiUrls.map(::hostOf) + imageHosts).any { it.equals(endpoint.domain, true) }) { "Host is not in the active Picacg route pools" }
        require(
            (apiRouteRules.entries + imageRouteRules.entries).any { (host, addresses) ->
                host.equals(endpoint.domain, true) && addresses.any { it.trim() == endpoint.ip }
            },
        ) { "IP is not configured for this Picacg host" }
        return base.newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (!hostname.equals(endpoint.domain, true)) {
                        throw java.net.UnknownHostException("Diagnostic probe is pinned to one host")
                    }
                    return listOf(InetAddress.getByName(endpoint.ip))
                }
            })
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .proxy(Proxy.NO_PROXY)
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
    }

    /*
     * Rewrites every Picacg image bucket host to a reachable mirror. Credentials, query and fragment
     * are preserved for content paths.
     *
     * Routing is bucket-aware and mirrors the official contract ({fileServer}/static/{path}):
     *  1. Cover thumbnails `/static/tobeimg/...` (their originals live ~59/60 in storage1, not storage-b)
     *     become the selected root's imgproxy UNSIGNED url `/insecure/{rs}/{b64}`; the embedded base64
     *     points at the real bucket, so no cross-bucket guess is made and no login/token is required.
     *  2. Reader pages `/static/tobs`, `/static/sub_storage_*` stay on the selected storage-b source
     *     (img nodes answer 502 for tobs).
     *  3. Non-content bucket files (direct-cover / avatar on storage1, …) are encoded into imgproxy
     *     insecure urls as well, so storage1 items load through the same verified mirror.
     *  4. Hosts outside the bucket/mirror set and already-mirror urls are left untouched.
     */
    /**
     * All mirror candidates for one image, ordered fastest-first by the independent speed test.
     * [PicacgImageFallbackInterceptor] walks these on failure, so a cold/failed mirror never leaves a
     * fixed cover or page permanently broken.
     *
     * Covers and reader pages use distinct services (covers on imgproxy `img.{root}`, pages on the
     * `storage-b.{root}` bucket); both fan out across every root:
     *  - `/static/tobeimg/...` thumbnails → `img.{root}/insecure/...` (embedded b64 names the real bucket).
     *  - `/static/tobs`, `/static/sub_storage_*` pages → `storage-b.{root}` (img nodes answer 502).
     *  - already-mirror `/insecure` urls → swap only the `img.{root}` host.
     *  - storage1/storage-b direct covers/avatars → `img.{root}/insecure` fetching the real origin.
     * Hosts outside the bucket/mirror set yield a single untouched url.
     */
    fun candidateImageUrls(url: String): List<String> = runCatching {
        val original = URI(url)
        if (original.scheme !in setOf("http", "https") || original.host.isNullOrBlank() || original.rawUserInfo != null) {
            return@runCatching listOf(url)
        }
        val host = original.host
        val rawPath = original.rawPath.orEmpty()
        // Covers and reader pages are measured and ranked independently.
        val coverRoots = rankedContentHosts(forContent = false)
        val contentRoots = rankedContentHosts(forContent = true)

        val isTobeimg = rawPath.startsWith(PicacgBuiltinNetwork.COVER_PROXY_PREFIX, ignoreCase = true)
        val isContent = PicacgBuiltinNetwork.isContentPath(rawPath)
        val isInsecureMirror = PicacgBuiltinNetwork.isCoverHost(host) && rawPath.startsWith("/insecure", ignoreCase = true)
        val isBucketDirect = PicacgBuiltinNetwork.isImageBucketHost(host)

        val out = LinkedHashSet<String>()
        when {
            // 1) Cover thumbnails -> imgproxy insecure (base64 encodes the real storage1/storage-b origin).
            isTobeimg -> coverRoots.forEach { contentHost ->
                PicacgBuiltinNetwork.coverHostFor(contentHost)?.let { coverHost ->
                    PicacgBuiltinNetwork.coverInsecureFromTobeimg(rawPath, coverHost)?.let(out::add)
                }
            }
            // 2) Reader pages stay on storage-b; never route tobs/sub_storage through imgproxy.
            isContent -> contentRoots.forEach { contentHost -> out.add(rebuildImageUrl(original, contentHost, rawPath)) }
            // 3) Already an imgproxy url: swap only the img host, keep the (self-contained) options/b64.
            isInsecureMirror -> coverRoots.forEach { contentHost ->
                PicacgBuiltinNetwork.coverHostFor(contentHost)?.let { coverHost ->
                    out.add(rebuildImageUrl(original, coverHost, rawPath))
                }
            }
            // 4) Direct bucket covers/avatars (storage1/storage-b) -> imgproxy insecure fetch of real origin.
            isBucketDirect -> coverRoots.forEach { contentHost ->
                PicacgBuiltinNetwork.coverHostFor(contentHost)?.let { coverHost ->
                    out.add(PicacgBuiltinNetwork.coverInsecureFromOrigin(original.toASCIIString(), coverHost))
                }
            }
            // 5) Other static paths already on a storage-b mirror -> fan out across content roots.
            _state.value.imageHosts.any { it.equals(host, true) } ->
                contentRoots.forEach { contentHost -> out.add(rebuildImageUrl(original, contentHost, rawPath)) }
            else -> return@runCatching listOf(url)
        }
        if (out.isEmpty()) listOf(url) else out.toList()
    }.getOrDefault(listOf(url))

    /** The fastest candidate; kept for call sites that still take a single url. */
    fun rewriteImageUrl(url: String): String = candidateImageUrls(url).firstOrNull() ?: url

    private fun rebuildImageUrl(original: URI, host: String, path: String): String = buildString {
        append(original.scheme).append("://").append(host)
        if (original.port != -1) append(':').append(original.port)
        append(path)
        original.rawQuery?.let { append('?').append(it) }
        original.rawFragment?.let { append('#').append(it) }
    }.let(::URI).toASCIIString()

    /** A verified sample may be supplied only from an actual authenticated API response by the Picacg repository. */
    @Volatile private var _verifiedImageSampleUrl: String? = null
    val verifiedImageSampleUrl: String? get() = _verifiedImageSampleUrl

    fun setVerifiedImageSampleUrlFromApiResponse(url: String?) {
        _verifiedImageSampleUrl = url?.takeIf { candidate ->
            runCatching {
                val uri = URI(candidate)
                uri.scheme in setOf("http", "https") && uri.host != null &&
                    (
                        PicacgBuiltinNetwork.isImageBucketHost(uri.host) ||
                            PicacgBuiltinNetwork.isCoverHost(uri.host)
                        )
            }.getOrDefault(false)
        }
    }

    @Synchronized
    fun clearConfiguration() {
        val old = _state.value
        apiRouteRules = emptyMap()
        imageRouteRules = emptyMap()
        dnsFallbackRules = emptyMap()
        routeConfigRevision++
        _state.value = State(revision = old.revision + 1)
        _verifiedImageSampleUrl = null
    }

    private fun persistedEndpoint(domainKey: String, ipKey: String): DomainIp? {
        val p = prefs() ?: return null
        val domain = p.getString(domainKey, null)?.takeIf(String::isNotBlank) ?: return null
        val ip = p.getString(ipKey, null)?.takeIf(String::isNotBlank) ?: return null
        return DomainIp(domain, ip).takeIf { isIpv4Literal(ip) }
    }

    private fun android.content.SharedPreferences.Editor.persistEndpoint(prefix: String, endpoint: DomainIp?) {
        if (endpoint == null) {
            remove("${prefix}_domain").remove("${prefix}_ip")
        } else {
            putString("${prefix}_domain", endpoint.domain).putString("${prefix}_ip", endpoint.ip)
        }
    }

    private fun hostOf(url: String): String = URI(url).host.orEmpty()
    private fun withTrailingSlash(url: String): String = url.trimEnd('/') + "/"
    private fun isIpv4Literal(ip: String): Boolean = ip.matches(Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")) &&
        ip.split('.').all { it.toIntOrNull() in 0..255 }
}
