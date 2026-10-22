package com.zycomic.app.net

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Independent anonymous connectivity checks for the Maccms recommendation hosts. */
object MaccmsSpeedTester {
    val routes: List<String> = listOf("macapi1.com", "macapi2.com", "macapi3.com")

    data class Probe(
        val route: String,
        val elapsedMs: Long?,
        val success: Boolean,
        val httpStatus: Int? = null,
        val error: String? = null,
        val testedAt: Long = System.currentTimeMillis(),
        val tested: Boolean = true,
    ) {
        val latencyMs: Long? get() = elapsedMs
        val reachable: Boolean get() = success
        val statusCode: Int? get() = httpStatus
    }

    data class Snapshot(
        val probes: List<Probe> = emptyList(),
        val selectedRoute: String? = null,
        val quick: Boolean = false,
        val completedAtEpochMs: Long? = null,
        val lines3Ip: String? = null,
    ) {
        val completedAt: Long? get() = completedAtEpochMs
    }

    internal data class RouteSelection(val route: String, val generation: Long, val lines3Ip: String?)

    private val lock = Any()
    private val runMutex = Mutex()
    private val _snapshot = MutableStateFlow(loadSnapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()
    private val _selectedRoute = MutableStateFlow(loadSelectedRoute() ?: _snapshot.value.selectedRoute ?: routes.first())
    val selectedRoute: StateFlow<String?> = _selectedRoute.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _logRevision = MutableStateFlow(0L)
    val logRevision: StateFlow<Long> = _logRevision.asStateFlow()
    private var generation = 0L
    // Never restored from preferences: a process restart is not a current speed-test round.
    private var currentLines3Ip: String? = null

    /** Retire in-flight recommendation selections before a new Picacg measurement starts. */
    internal fun invalidateLines3Ip() = synchronized(lock) {
        currentLines3Ip = null
        generation++
    }
    private val history = loadHistory()
    private val runtimeEvents = loadRuntimeEvents()

    @Volatile private var probeTransport: suspend (String, String) -> Probe = ::probe

    /** Test seam for deterministic server, cancellation, and concurrency tests. */
    internal fun installProbeTransportForTests(transport: suspend (String, String) -> Probe) {
        probeTransport = transport
    }

    internal fun resetProbeTransportForTests() {
        probeTransport = ::probe
    }

    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * Quick mode prioritizes the historically fastest host, then checks the remaining hosts too.
     * The pool has only three hosts, so checking all three is still consistent with a top-five probe.
     */
    suspend fun run(
        quick: Boolean = false,
        lines3Ip: String? = null,
        isCurrent: () -> Boolean = { true },
        beforeApply: suspend () -> Boolean = { true },
    ): Snapshot = runMutex.withLock {
        _running.value = true
        try {
            val startingGeneration = synchronized(lock) { generation }
            val prior = synchronized(lock) { _snapshot.value }
            val order = if (quick) {
                val fastest = prior.probes.filter { it.success && it.elapsedMs != null }
                    .minByOrNull { it.latencyMs!! }?.route
                listOfNotNull(fastest) + routes.filterNot { it == fastest }
            } else {
                routes
            }
            val ip = lines3Ip?.takeIf { MaccmsPinnedTransport.address(it) != null }
            val probes = if (ip == null) {
                order.map { route -> Probe(route, null, false, error = "No current-run lines3 IP; not tested", tested = false) }
            } else {
                coroutineScope { order.map { route -> async { probeTransport(route, ip) } }.awaitAll() }
            }
            currentCoroutineContext().ensureActive()
            val selected = probes.filter { it.success && it.elapsedMs != null }
                .minByOrNull { it.elapsedMs!! }?.route ?: _selectedRoute.value
            val result = Snapshot(probes = probes, selectedRoute = selected, quick = quick, completedAtEpochMs = System.currentTimeMillis(), lines3Ip = ip)
            if (isCurrent() && beforeApply()) {
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (isCurrent() && generation == startingGeneration) {
                        // Even an unchanged host/IP starts a new telemetry generation.
                        generation++
                        currentLines3Ip = ip
                        _snapshot.value = result
                        _selectedRoute.value = selected
                        selected?.let { preferences()?.edit()?.putString(KEY_SELECTED_ROUTE, it)?.apply() }
                        saveCurrent(result)
                        history.add(0, result)
                        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
                        saveHistory(history)
                    }
                }
            }
            result
        } finally {
            _running.value = false
        }
    }

    /** Runtime request telemetry also updates the independent route choice. */
    internal fun recordRuntime(
        route: String,
        latencyMs: Long,
        statusCode: Int? = null,
        failure: Throwable? = null,
        failover: Boolean = false,
        expectedGeneration: Long? = null,
    ): Long? {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val selectionBefore = _selectedRoute.value
            val canSelect = expectedGeneration == generation
            val nextRoute = if (!canSelect) {
                selectionBefore
            } else if (failover) {
                val failedIndex = routes.indexOf(route).takeIf { it >= 0 } ?: 0
                routes[(failedIndex + 1) % routes.size]
            } else if (failure == null) {
                route
            } else {
                selectionBefore
            }
            if (canSelect && nextRoute != selectionBefore) {
                generation++
                _selectedRoute.value = nextRoute
                preferences()?.edit()?.putString(KEY_SELECTED_ROUTE, nextRoute)?.apply()
            }
            runtimeEvents.add(
                JSONObject()
                    .put("route", route)
                    .put("elapsedMs", latencyMs)
                    .put("httpStatus", statusCode ?: JSONObject.NULL)
                    .put("success", statusCode?.let { it in 200..299 || it in 400..499 } ?: (failure == null))
                    .put("recommendationSuccess", failure == null)
                    .put("failure", failure?.message ?: JSONObject.NULL)
                    .put("eventAtEpochMs", now)
                    .put("selectedRouteBefore", selectionBefore ?: JSONObject.NULL)
                    .put("selectedRouteAfter", _selectedRoute.value ?: JSONObject.NULL)
                    .put("failoverRequested", failover)
                    .put("failover", failover && canSelect && nextRoute != selectionBefore),
            )
            while (runtimeEvents.size > MAX_RUNTIME_EVENTS) runtimeEvents.removeAt(0)
            saveRuntimeEvents()
            _logRevision.value += 1
            return if (canSelect) generation else null
        }
    }

    internal fun routeSelection(): RouteSelection = synchronized(lock) {
        RouteSelection(_selectedRoute.value?.takeIf { it in routes } ?: routes.first(), generation, currentLines3Ip)
    }

    internal fun currentRoute(): String = synchronized(lock) {
        _selectedRoute.value?.takeIf { it in routes } ?: routes.first()
    }

    /** Independent, machine-readable Maccms-only diagnostic log. */
    fun logJson(): JSONObject = synchronized(lock) {
        JSONObject()
            .put("service", "maccms")
            .put("hosts", JSONArray(routes))
            .put("selectedRoute", _selectedRoute.value ?: JSONObject.NULL)
            .put("currentLines3Ip", currentLines3Ip ?: JSONObject.NULL)
            .put("measurement", "Anonymous HTTPS HEAD /; 200-299 and 400-499 are reachable, 300-399 and 500+ are not; does not validate recommendation API")
            .put("eventSuccessMeaning", "HTTP/connectivity reachability: 200-299 and 400-499 (including 404, 401, 403, 429), excluding redirects; recommendationSuccess records the recommendation request outcome separately")
            .put("snapshot", snapshotJson(_snapshot.value))
            .put("history", JSONArray().also { array -> history.forEach { array.put(snapshotJson(it)) } })
            .put("events", JSONArray().also { array -> runtimeEvents.forEach { array.put(JSONObject(it.toString())) } })
            .put("runtimeEvents", JSONArray().also { array -> runtimeEvents.forEach { array.put(JSONObject(it.toString())) } })
    }

    private suspend fun probe(route: String, lines3Ip: String): Probe {
        val started = System.nanoTime()
        val now = System.currentTimeMillis()
        return try {
            val request = Request.Builder().url("https://$route/".toHttpUrl()).head().build()
            MaccmsPinnedTransport.request(MaccmsPinnedTransport.client(probeClient, route, lines3Ip), request) { response ->
                val status = response.code
                Probe(route, elapsed(started), status in 200..299 || status in 400..499, status, testedAt = now)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            Probe(route, elapsed(started), false, error = failure.message ?: failure.javaClass.simpleName, testedAt = now)
        }
    }

    private fun elapsed(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private fun preferences() = DevConfig.appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadHistory(): MutableList<Snapshot> = runCatching {
        val array = JSONArray(preferences()?.getString(KEY_HISTORY, "[]") ?: "[]")
        (0 until array.length()).mapNotNull { parseSnapshot(array.optJSONObject(it)) }.toMutableList()
    }.getOrDefault(mutableListOf())

    private fun loadSnapshot(): Snapshot = runCatching {
        val prefs = preferences()
        parseSnapshot(prefs?.getString(KEY_CURRENT, null)?.let(::JSONObject)) ?: loadHistory().firstOrNull() ?: Snapshot()
    }.getOrDefault(Snapshot())

    private fun loadSelectedRoute(): String? = preferences()?.getString(KEY_SELECTED_ROUTE, null)?.takeIf { it in routes }

    private fun saveCurrent(value: Snapshot) {
        preferences()?.edit()?.putString(KEY_CURRENT, snapshotJson(value).toString())?.apply()
    }

    private fun loadRuntimeEvents(): MutableList<JSONObject> = runCatching {
        val array = JSONArray(preferences()?.getString(KEY_RUNTIME_EVENTS, "[]") ?: "[]")
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { event -> JSONObject(event.toString()) } }
            .takeLast(MAX_RUNTIME_EVENTS).toMutableList()
    }.getOrDefault(mutableListOf())

    private fun saveRuntimeEvents() {
        val value = JSONArray().also { array -> runtimeEvents.forEach { array.put(it) } }.toString()
        preferences()?.edit()?.putString(KEY_RUNTIME_EVENTS, value)?.apply()
    }

    private fun saveHistory(items: List<Snapshot>) {
        val json = JSONArray().also { array -> items.forEach { array.put(snapshotJson(it)) } }.toString()
        preferences()?.edit()?.putString(KEY_HISTORY, json)?.apply()
    }

    private fun snapshotJson(value: Snapshot): JSONObject = JSONObject()
        .put(
            "probes",
            JSONArray().also { array ->
                value.probes.forEach { probe ->
                    array.put(
                        JSONObject().put("route", probe.route).put("elapsedMs", probe.elapsedMs ?: JSONObject.NULL)
                            .put("success", probe.success).put("httpStatus", probe.httpStatus ?: JSONObject.NULL)
                            .put("error", probe.error ?: JSONObject.NULL).put("testedAt", probe.testedAt)
                            .put("tested", probe.tested),
                    )
                }
            },
        )
        .put("lines3Ip", value.lines3Ip ?: JSONObject.NULL)
        .put("selectedRoute", value.selectedRoute ?: JSONObject.NULL)
        .put("quick", value.quick)
        .put("completedAtEpochMs", value.completedAtEpochMs ?: JSONObject.NULL)

    private fun parseSnapshot(value: JSONObject?): Snapshot? = value?.let { json ->
        val items = json.optJSONArray("probes") ?: JSONArray()
        val probes = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val route = item.optString("route").takeIf { it in routes } ?: return@mapNotNull null
            val elapsedField = if (item.has("elapsedMs")) "elapsedMs" else "latencyMs"
            val successField = if (item.has("success")) "success" else "reachable"
            val statusField = if (item.has("httpStatus")) "httpStatus" else "statusCode"
            Probe(
                route = route,
                elapsedMs = item.optLong(elapsedField).takeIf { !item.isNull(elapsedField) },
                success = item.optBoolean(successField),
                httpStatus = item.optInt(statusField).takeIf { !item.isNull(statusField) },
                error = item.optString("error").takeUnless { item.isNull("error") },
                testedAt = item.optLong("testedAt"),
                tested = item.optBoolean("tested", true),
            )
        }
        Snapshot(
            probes,
            json.optString("selectedRoute").takeIf { it in routes },
            json.optBoolean("quick"),
            json.optLong("completedAtEpochMs").takeIf { !json.isNull("completedAtEpochMs") },
            json.optString("lines3Ip").takeUnless { json.isNull("lines3Ip") || it.isBlank() },
        )
    }

    private const val PREFS = "zycomic_maccms_speed_test"
    private const val KEY_HISTORY = "history_json"
    private const val KEY_CURRENT = "current_snapshot_json"
    private const val KEY_SELECTED_ROUTE = "selected_route"
    private const val KEY_RUNTIME_EVENTS = "runtime_events_json"
    private const val MAX_HISTORY = 20
    private const val MAX_RUNTIME_EVENTS = 100
}
