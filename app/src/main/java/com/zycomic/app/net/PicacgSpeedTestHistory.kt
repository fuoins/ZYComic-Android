package com.zycomic.app.net

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** Persisted, Picacg-only measurements. Identity includes the exact request and transport/probe contract. */
internal object PicacgSpeedTestHistoryPolicy {
    const val RETEST_LIMIT = 5
    const val PLATFORM = "picacg"
    const val API_PHASE = "api"
    const val IMAGE_PHASE = "image"
    const val API_ROUTE_CONDITION = "pinned-ip;configured-route-rules-or-system-dns;https;GET-root;redirects-disabled;non-redirect-http-response"
    const val IMAGE_ROUTE_CONDITION = "pinned-ip;configured-route-rules-or-system-dns;https;GET-imgproxy-tile;redirects-disabled;200-image-magic"

    data class Identity(
        val platform: String,
        val phase: String,
        /** API base URL for API records; the exact real sample URL for image records. */
        val url: String,
        val domain: String,
        val ip: String,
        val routeCondition: String,
        val probeCondition: String,
    )

    data class Measurement(
        val identity: Identity,
        val success: Boolean,
        val elapsedMs: Long,
        val measuredAtEpochMs: Long,
        val httpStatus: Int? = null,
        val contentType: String? = null,
        val imageDecoded: Boolean? = null,
    ) {
        val endpoint: DomainIp get() = DomainIp(identity.domain, identity.ip)
    }

    data class PhaseHistory(val remoteVersion: Int?, val measurements: List<Measurement> = emptyList())

    fun canUseAtVersion(historyVersion: Int?, activeVersion: Int?): Boolean = historyVersion == activeVersion

    /** Reconciliation is authorized only by an explicitly requested, strictly newer numbered config. */
    fun shouldReconcile(requested: Boolean, historyVersion: Int?, activeVersion: Int?): Boolean =
        requested && historyVersion != null && activeVersion != null && activeVersion > historyVersion

    /** Select the first fixed-size ranked slice, including previous failures; never expand after failures. */
    fun selectRankedTop(history: PhaseHistory, limit: Int = RETEST_LIMIT): List<Measurement> =
        history.measurements.take(limit.coerceAtLeast(0))

    /** On a newer config, preserve exact identities still present, probe only additions, and drop removals. */
    fun reconcile(
        previous: PhaseHistory,
        activeVersion: Int?,
        activeIdentities: Set<Identity>,
        fresh: List<Measurement>,
    ): PhaseHistory = PhaseHistory(
        activeVersion,
        sorted((previous.measurements.filter { it.identity in activeIdentities } + fresh).distinctBy(Measurement::identity)),
    )

    /** A same-version quick pass refreshes selected identities and leaves every other timestamp untouched. */
    fun mergeQuick(previous: PhaseHistory, selected: Set<Identity>, fresh: List<Measurement>): PhaseHistory =
        PhaseHistory(
            previous.remoteVersion,
            sorted(
                buildList {
                    val freshByIdentity = fresh.associateBy(Measurement::identity)
                    previous.measurements.forEach { old ->
                        if (old.identity in selected) {
                            freshByIdentity[old.identity]?.let(::add)
                        } else {
                            add(old)
                        }
                    }
                    fresh.forEach { if (none { existing -> existing.identity == it.identity }) add(it) }
                },
            ),
        )

    /** A full same-version pass refreshes measured identities, retaining the untested historical tail. */
    fun mergeFull(previous: PhaseHistory, fresh: List<Measurement>): PhaseHistory =
        PhaseHistory(
            previous.remoteVersion,
            sorted(
                buildList {
                    val freshByIdentity = fresh.associateBy(Measurement::identity)
                    previous.measurements.forEach { old -> add(freshByIdentity[old.identity] ?: old) }
                    fresh.forEach { if (none { existing -> existing.identity == it.identity }) add(it) }
                },
            ),
        )

    fun freshOnly(version: Int?, fresh: List<Measurement>): PhaseHistory = PhaseHistory(version, sorted(fresh.distinctBy(Measurement::identity)))

    fun sorted(measurements: List<Measurement>): List<Measurement> = measurements.sortedWith(measurementOrder)

    private val measurementOrder = compareBy<Measurement>(
        { if (it.success) 0 else 1 },
        { if (it.success) it.elapsedMs else Long.MAX_VALUE },
        { it.identity.domain.lowercase(Locale.ROOT) },
        { it.identity.ip },
        { it.identity.url },
        { it.identity.routeCondition },
        { it.identity.probeCondition },
    )
}

/** SharedPreferences adapter kept separate from the pure policy so it can be tested offline. */
internal object PicacgSpeedTestHistoryStorage {
    private const val PREFS_NAME = "zycomic_picacg_speed_test_history"
    private const val API_KEY = "api_history_v1"
    private const val IMAGE_KEY = "image_history_v1"

    fun loadApi(): PicacgSpeedTestHistoryPolicy.PhaseHistory = load(API_KEY, PicacgSpeedTestHistoryPolicy.API_PHASE)

    fun loadImages(): PicacgSpeedTestHistoryPolicy.PhaseHistory = load(IMAGE_KEY, PicacgSpeedTestHistoryPolicy.IMAGE_PHASE)

    fun saveApi(history: PicacgSpeedTestHistoryPolicy.PhaseHistory) = save(API_KEY, history)

    fun saveImages(history: PicacgSpeedTestHistoryPolicy.PhaseHistory) = save(IMAGE_KEY, history)

    private fun preferences() = DevConfig.appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun load(key: String, phase: String): PicacgSpeedTestHistoryPolicy.PhaseHistory {
        val raw = runCatching { preferences()?.getString(key, null) }.getOrNull() ?: return PicacgSpeedTestHistoryPolicy.PhaseHistory(null)
        return runCatching {
            val root = JSONObject(raw)
            val version = if (root.isNull("remoteVersion")) null else root.getInt("remoteVersion")
            val entries = root.optJSONArray("measurements") ?: JSONArray()
            val measurements = buildList {
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    val identity = PicacgSpeedTestHistoryPolicy.Identity(
                        platform = item.optString("platform"),
                        phase = item.optString("phase"),
                        url = item.optString("url"),
                        domain = item.optString("domain"),
                        ip = item.optString("ip"),
                        routeCondition = item.optString("routeCondition"),
                        probeCondition = item.optString("probeCondition"),
                    )
                    if (!valid(identity, phase)) continue
                    val measuredAt = item.optLong("measuredAtEpochMs", 0L)
                    val elapsed = item.optLong("elapsedMs", -1L)
                    if (measuredAt <= 0L || elapsed < 0L) continue
                    add(
                        PicacgSpeedTestHistoryPolicy.Measurement(
                            identity = identity,
                            success = item.optBoolean("success"),
                            elapsedMs = elapsed,
                            measuredAtEpochMs = measuredAt,
                            httpStatus = if (item.isNull("httpStatus")) null else item.optInt("httpStatus"),
                            contentType = item.optString("contentType").takeIf(String::isNotEmpty),
                            imageDecoded = if (item.has("imageDecoded") && !item.isNull("imageDecoded")) item.optBoolean("imageDecoded") else null,
                        ),
                    )
                }
            }
            PicacgSpeedTestHistoryPolicy.PhaseHistory(version, PicacgSpeedTestHistoryPolicy.sorted(measurements))
        }.getOrDefault(PicacgSpeedTestHistoryPolicy.PhaseHistory(null))
    }

    private fun save(key: String, history: PicacgSpeedTestHistoryPolicy.PhaseHistory) {
        val root = JSONObject().put("remoteVersion", history.remoteVersion ?: JSONObject.NULL)
            .put(
                "measurements",
                JSONArray().apply {
                    history.measurements.forEach { measurement ->
                        val identity = measurement.identity
                        put(
                            JSONObject()
                                .put("platform", identity.platform)
                                .put("phase", identity.phase)
                                .put("url", identity.url)
                                .put("domain", identity.domain)
                                .put("ip", identity.ip)
                                .put("routeCondition", identity.routeCondition)
                                .put("probeCondition", identity.probeCondition)
                                .put("success", measurement.success)
                                .put("elapsedMs", measurement.elapsedMs)
                                .put("measuredAtEpochMs", measurement.measuredAtEpochMs)
                                .put("httpStatus", measurement.httpStatus ?: JSONObject.NULL)
                                .put("contentType", measurement.contentType ?: JSONObject.NULL)
                                .put("imageDecoded", measurement.imageDecoded ?: JSONObject.NULL),
                        )
                    }
                },
            ).toString()
        runCatching { preferences()?.edit()?.putString(key, root)?.apply() }
    }

    private fun valid(identity: PicacgSpeedTestHistoryPolicy.Identity, expectedPhase: String): Boolean {
        if (identity.platform != PicacgSpeedTestHistoryPolicy.PLATFORM || identity.phase != expectedPhase ||
            identity.domain.isBlank() || !isIpAddress(identity.ip) || identity.url.isBlank()
        ) {
            return false
        }
        val uri = runCatching { URI(identity.url) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("http", "https")) return false
        // Image probes are served by the img.{root} mirror, which is the same-root anycast mirror of the
        // recorded storage-b.{root} source; both hosts are acceptable for an image identity.
        val hostMatches = if (expectedPhase == PicacgSpeedTestHistoryPolicy.IMAGE_PHASE) {
            uri.host.equals(identity.domain, ignoreCase = true) ||
                uri.host.equals(PicacgBuiltinNetwork.coverHostFor(identity.domain), ignoreCase = true)
        } else {
            uri.host.equals(identity.domain, ignoreCase = true)
        }
        if (!hostMatches) return false
        return when (expectedPhase) {
            PicacgSpeedTestHistoryPolicy.API_PHASE -> hasRouteSource(identity.routeCondition, PicacgSpeedTestHistoryPolicy.API_ROUTE_CONDITION) &&
                identity.probeCondition == "GET-root;redirects-disabled;accept-non-redirect-http-status"
            PicacgSpeedTestHistoryPolicy.IMAGE_PHASE -> hasRouteSource(identity.routeCondition, PicacgSpeedTestHistoryPolicy.IMAGE_ROUTE_CONDITION) &&
                identity.probeCondition == "GET-imgproxy-tile;redirects-disabled;status-200;image-content-type;image-magic"
            else -> false
        }
    }

    private fun hasRouteSource(condition: String, base: String): Boolean =
        condition == "$base;ip-source=configured-rules" || condition == "$base;ip-source=system-dns"

    private fun isIpAddress(ip: String): Boolean = runCatching {
        if (ip.contains(':')) {
            // A colon forces the numeric IPv6 parser; never pass a hostname to DNS here.
            ip.matches(Regex("[0-9a-fA-F:.%_-]+")) && InetAddress.getByName(ip) is Inet6Address
        } else {
            val octets = ip.split('.')
            octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }
        }
    }.getOrDefault(false)
}
