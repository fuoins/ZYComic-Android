package com.zycomic.app.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.json.JSONArray
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
        val remoteVersion: Int? = null,
        val revision: Long = 0L,
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
    val isApiManuallyPinned: Boolean get() = prefs()?.getBoolean("manual_api_selection", false) == true
    val isImageManuallyPinned: Boolean get() = prefs()?.getBoolean("manual_image_selection", false) == true

    private fun prefs() = DevConfig.appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Install only already-validated remote values; no built-in domain, IP, or fallback pool is introduced. */
    @Synchronized
    fun applyRemoteConfig(apiUrls: List<String>, imageHosts: List<String>, rules: Map<String, List<String>>, version: Int?) {
        val normalizedUrls = apiUrls.map(String::trim).filter(String::isNotEmpty).distinctBy { hostOf(it).lowercase(Locale.ROOT) }
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
        )
        _state.value = next
        routeConfigRevision++
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
        // Keep route-rule snapshots in memory only; source lists and rules always come from DevConfig's validated snapshot.
        apiRouteRules = apiRules
        imageRouteRules = imageRules
    }

    @Volatile private var apiRouteRules: Map<String, List<String>> = emptyMap()

    @Volatile private var imageRouteRules: Map<String, List<String>> = emptyMap()

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
        _state.value = old.copy(
            apiIndex = index,
            imageHost = imageHost,
            pinnedApi = persistedEndpoint("api_pin_domain", "api_pin_ip"),
            pinnedImage = persistedEndpoint("image_pin_domain", "image_pin_ip"),
            revision = old.revision + 1,
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
        _state.value = state.copy(apiIndex = index, revision = state.revision + 1)
        return true
    }

    @Synchronized
    fun selectImageHost(host: String, manual: Boolean = true): Boolean {
        val candidate = _state.value.imageHosts.firstOrNull { it.equals(host.trim(), true) } ?: return false
        prefs()?.edit()?.putBoolean("manual_image_selection", manual)
            ?.apply { if (manual) putString("selected_image_host", candidate) else remove("selected_image_host") }?.apply()
        _state.value = _state.value.copy(imageHost = candidate, revision = _state.value.revision + 1)
        return true
    }

    /** Pin an API request to a host/IP pair obtained from the configured rules or measured DNS. */
    @Synchronized
    fun pinApiEndpoint(endpoint: DomainIp?): Boolean = setPin(endpoint, true)

    /** Pin image requests independently from API traffic. */
    @Synchronized
    fun pinImageEndpoint(endpoint: DomainIp?): Boolean = setPin(endpoint, false)

    /** Apply a measured endpoint without changing the user's manual-selection preference. */
    fun applyAutomaticApiEndpoint(endpoint: DomainIp?): Boolean = setPin(endpoint, true, manual = false)

    /** Apply a measured image endpoint without changing the user's manual-selection preference. */
    fun applyAutomaticImageEndpoint(endpoint: DomainIp?): Boolean = setPin(endpoint, false, manual = false)

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
            _state.value = current.copy(pinnedApi = endpoint, revision = current.revision + 1)
        } else {
            prefs?.edit()?.apply {
                putBoolean("manual_image_selection", manual)
                putString("image_pin_domain", endpoint?.domain)
                putString("image_pin_ip", endpoint?.ip)
            }?.apply()
            _state.value = current.copy(pinnedImage = endpoint, revision = current.revision + 1)
        }
        return true
    }

    fun routeRules(): Map<String, List<String>> = apiRouteRules + imageRouteRules
    fun apiRouteRules(): Map<String, List<String>> = apiRouteRules.toMap()
    fun imageRouteRules(): Map<String, List<String>> = imageRouteRules.toMap()
    fun resolveIps(host: String): List<String> = (apiRouteRules + imageRouteRules).entries
        .firstOrNull { it.key.equals(host, true) }?.value.orEmpty()

    /** DNS policy for Picacg clients; applies only Picacg's remote rules and endpoint pins. */
    fun dns(): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val pin = _state.value.let { state ->
                state.pinnedApi?.takeIf { it.domain.equals(hostname, true) }?.ip
                    ?: state.pinnedImage?.takeIf { it.domain.equals(hostname, true) }?.ip
            }
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
                    base.dns.lookup(hostname)
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

    /** Rewrites only a configured source host. Credentials, paths, query and fragment are preserved verbatim. */
    fun rewriteImageUrl(url: String): String {
        val target = currentImageHost ?: return url
        return runCatching {
            val original = URI(url)
            if (original.scheme !in setOf("http", "https") || original.host.isNullOrBlank() || original.rawUserInfo != null) return url
            if (_state.value.imageHosts.none { it.equals(original.host, true) }) return url
            buildString {
                append(original.scheme).append("://").append(target)
                if (original.port != -1) append(':').append(original.port)
                append(original.rawPath.orEmpty())
                original.rawQuery?.let { append('?').append(it) }
                original.rawFragment?.let { append('#').append(it) }
            }.let(::URI).toASCIIString()
        }.getOrDefault(url)
    }

    /** A verified sample may be supplied only from an actual authenticated API response by the Picacg repository. */
    @Volatile private var _verifiedImageSampleUrl: String? = null
    val verifiedImageSampleUrl: String? get() = _verifiedImageSampleUrl

    fun setVerifiedImageSampleUrlFromApiResponse(url: String?) {
        _verifiedImageSampleUrl = url?.takeIf { candidate ->
            runCatching {
                val uri = URI(candidate)
                uri.scheme in setOf("http", "https") && uri.host != null &&
                    _state.value.imageHosts.any { it.equals(uri.host, true) }
            }.getOrDefault(false)
        }
    }

    @Synchronized
    fun clearConfiguration() {
        val old = _state.value
        _state.value = State(revision = old.revision + 1)
        routeConfigRevision++
        apiRouteRules = emptyMap()
        imageRouteRules = emptyMap()
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
