package com.zycomic.app.data.repository

import android.content.Context
import com.zycomic.app.net.JmApiInterceptor
import com.zycomic.app.net.JmAuthStore
import com.zycomic.app.net.JmNetworkModule
import com.zycomic.app.net.JmRouteManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth

/** JM check-in requests are isolated from the Manwa API and never persist a raw account ID. */
object JmCheckInRepository {
    private const val PREFS_NAME = "jm_check_in"
    private const val KEY_AUTO_ENABLED = "auto_enabled"
    private const val KEY_ATTEMPT_PREFIX = "attempt_"
    private const val KEY_SUCCESS_PREFIX = "success_"
    private val mutex = Mutex()
    private val makeupMutex = Mutex()

    /** UI preference accessor; automatic check-ins are enabled for existing installs by default. */
    fun isAutoEnabled(context: Context): Boolean = preferences(context).getBoolean(KEY_AUTO_ENABLED, true)

    fun setAutoEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply()
    }

    /** Restore the current account's successful status from local storage without making a request. */
    fun todaySuccessMessage(context: Context, today: String): String? {
        val accountHash = currentValidUid()?.let(::hashAccount)
        return JmCheckInUiState.restoredMessage(accountHash, today) { hash ->
            preferences(context).getString(KEY_SUCCESS_PREFIX + hash, null)
        }
    }

    suspend fun checkIn(context: Context, automatic: Boolean): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = preferences(context)
            if (automatic && !prefs.getBoolean(KEY_AUTO_ENABLED, true)) return@withLock null

            val uid = currentValidUid() ?: throw IOException("请先登录 JM 账号后再签到")
            val accountKey = hashAccount(uid)
            val today = LocalDate.now().toString()
            if (prefs.getString(KEY_SUCCESS_PREFIX + accountKey, null) == today) {
                return@withLock if (automatic) null else "今日已签到"
            }

            val backend = CheckInBackend { expectedUid -> ensureCurrentAccount(expectedUid) }
            val engine = JmCheckInEngine(
                backend = backend,
                state = object : JmCheckInState {
                    override fun isAutomaticEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_ENABLED, true)
                    override fun attemptedDate(accountHash: String): String? = prefs.getString(KEY_ATTEMPT_PREFIX + accountHash, null)
                    override fun successfulDate(accountHash: String): String? = prefs.getString(KEY_SUCCESS_PREFIX + accountHash, null)
                    override fun markAttempt(accountHash: String, date: String) {
                        check(prefs.edit().putString(KEY_ATTEMPT_PREFIX + accountHash, date).commit())
                    }
                    override fun markSuccess(accountHash: String, date: String) {
                        check(prefs.edit().putString(KEY_SUCCESS_PREFIX + accountHash, date).commit())
                    }
                    override fun currentAccountHash(): String? = currentValidUid()?.let(::hashAccount)
                },
                mutex = Mutex(),
            )
            // Use the process-wide lock above. The engine's injected mutex is not needed inside this already-locked call.
            try {
                engine.checkIn(uid, accountKey, today, automatic)
            } catch (error: IOException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                throw IOException("签到失败，请稍后重试")
            }
        }
    }

    /** Read-only calendar preview; this method never invokes either check-in endpoint. */
    internal suspend fun loadCalendar(): JmDailyCalendar = withContext(Dispatchers.IO) {
        val uid = currentValidUid() ?: throw IOException("请先登录 JM 账号")
        CheckInBackend(::ensureCurrentAccount).fetchCalendar(uid, JmAuthStore.sessionRevision)
    }

    internal suspend fun loadAwardYears(): List<String> = withContext(Dispatchers.IO) {
        val uid = currentValidUid() ?: throw IOException("请先登录 JM 账号")
        CheckInBackend(::ensureCurrentAccount).fetchAwardYears(uid, JmAuthStore.sessionRevision)
    }

    /** Fetches year-filtered artwork only; the month-value claim form is intentionally never called. */
    internal suspend fun loadMonthlyAwards(year: String): List<JmMonthlyAward> = withContext(Dispatchers.IO) {
        require(isValidAwardYear(year)) { "年份无效" }
        val uid = currentValidUid() ?: throw IOException("请先登录 JM 账号")
        CheckInBackend(::ensureCurrentAccount).fetchMonthlyAwards(uid, year, JmAuthStore.sessionRevision)
    }

    /** Called only after a user confirms the selected dates in the UI. */
    internal suspend fun makeup(
        context: Context,
        preview: JmDailyCalendar,
        dates: Set<LocalDate>,
        onProgress: (JmMakeupProgress) -> Unit = {},
    ): JmMakeupResult = withContext(Dispatchers.IO) {
        makeupMutex.withLock {
            val uid = currentValidUid() ?: throw IOException("请先登录 JM 账号")
            val accountHash = hashAccount(uid)
            val sessionRevision = JmAuthStore.sessionRevision
            fun verifySession() {
                ensureCurrentAccount(uid)
                if (JmAuthStore.sessionRevision != sessionRevision || preview.sessionRevision != sessionRevision) {
                    throw IOException("JM 登录状态已变化，请重新加载签到日历")
                }
            }
            verifySession()
            val today = LocalDate.now()
            val month = YearMonth.from(today)
            if (preview.accountHash != accountHash) throw IOException("JM 账号已切换，请重新加载签到日历")
            if (preview.month != month) throw IOException("月份已变化，请重新加载签到日历")
            val eligible = preview.makeupCandidates(today).map { it.date }.toSet()
            val requested = dates.sorted()
            if (requested.isEmpty() || requested.any { it !in eligible }) throw IOException("只能选择本月今天之前的缺签日期")

            // Fresh read protects against server-side changes since preview and makes retries idempotent.
            val backend = CheckInBackend(::ensureCurrentAccount)
            val fresh = backend.fetchCalendar(uid, sessionRevision)
            verifySession()
            if (fresh.month != month || fresh.dailyId != preview.dailyId) throw IOException("签到月份或活动已变化，请重新加载后再补签")
            val freshCandidates = fresh.makeupCandidates(today).map { it.date }.toSet()
            val prefs = preferences(context)
            val signedOnServer = fresh.days.filter { it.signed == true }.map { it.date }.toSet()
            val locallyCompleted = requested.filter { prefs.getBoolean(makeupKey(accountHash, it), false) }.toSet()
            val pending = requested.filter { date ->
                date in freshCandidates && !prefs.getBoolean(makeupKey(accountHash, date), false)
            }
            // Reconcile dates now signed on the server or previously completed locally.
            val reconciled = requested.filter { it in locallyCompleted || it in signedOnServer }
            if (requested.any { it !in pending && it !in reconciled }) throw IOException("部分日期状态已变化，请重新加载签到日历后再试")
            reconciled.forEach { date -> check(prefs.edit().putBoolean(makeupKey(accountHash, date), true).commit()) }

            val completed = mutableListOf<LocalDate>()
            var lastPostAt = prefs.getLong(makeupLastPostKey(accountHash), 0L)
            for ((index, date) in pending.withIndex()) {
                verifySession()
                if (YearMonth.from(LocalDate.now()) != month) throw IOException("跨月已停止补签；已完成日期已保存，请重新加载")
                if (lastPostAt != 0L) {
                    val remaining = MIN_MAKEUP_INTERVAL_MS - (System.currentTimeMillis() - lastPostAt)
                    if (remaining > 0) delay(remaining)
                }
                verifySession()
                if (YearMonth.from(LocalDate.now()) != month) throw IOException("跨月已停止补签；已完成日期已保存，请重新加载")
                try {
                    lastPostAt = System.currentTimeMillis()
                    check(prefs.edit().putLong(makeupLastPostKey(accountHash), lastPostAt).commit())
                    backend.submitMakeup(uid, preview.dailyId, date, sessionRevision)
                    check(prefs.edit().putBoolean(makeupKey(accountHash, date), true).commit())
                    completed += date
                    onProgress(JmMakeupProgress(completed.size, pending.size, date, null))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    onProgress(JmMakeupProgress(completed.size, pending.size, date, error.message ?: "补签失败"))
                    return@withLock JmMakeupResult(completed, date, error.message ?: "补签失败")
                }
            }
            JmMakeupResult(completed, null, null)
        }
    }

    private fun makeupKey(accountHash: String, date: LocalDate) = "makeup_${accountHash}_$date"
    private fun makeupLastPostKey(accountHash: String) = "makeup_last_post_$accountHash"
    private const val MIN_MAKEUP_INTERVAL_MS = 1_500L

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun currentValidUid(): String? = JmAuthStore.uid()
        ?.takeIf { it.matches(Regex("[0-9]+")) && JmAuthStore.isLoggedIn }

    private fun hashAccount(uid: String): String = MessageDigest.getInstance("SHA-256")
        .digest(uid.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun ensureCurrentAccount(expectedUid: String) {
        if (currentValidUid() != expectedUid) throw IOException("JM 账号已切换，请重新发起签到")
    }

    private class CheckInBackend(private val verifyAccount: (String) -> Unit) : JmCheckInApi {
        suspend fun fetchCalendar(uid: String, sessionRevision: Long): JmDailyCalendar = withContext(Dispatchers.IO) {
            verifySession(uid, sessionRevision)
            val base = JmRouteManager.baseUrl.trimEnd('/')
            if (base.isBlank()) throw IOException("JM 暂无可用线路")
            val request = Request.Builder().url(base.toHttpUrl().newBuilder().addPathSegment("daily").addQueryParameter("user_id", uid).build())
                .get().jmHeaders().build()
            executeRead(request, uid, sessionRevision) { response, raw ->
                checkErrorHeader(response)
                JmCheckInResponseParser.parseCalendar(raw, LocalDate.now(), hashAccount(uid), sessionRevision)
            }
        }

        suspend fun fetchAwardYears(uid: String, sessionRevision: Long): List<String> = withContext(Dispatchers.IO) {
            verifySession(uid, sessionRevision)
            val base = JmRouteManager.baseUrl.trimEnd('/')
            if (base.isBlank()) throw IOException("JM 暂无可用线路")
            val request = Request.Builder().url(base.toHttpUrl().newBuilder().addPathSegment("daily_list").addQueryParameter("user_id", uid).build())
                .get().jmHeaders().build()
            executeRead(request, uid, sessionRevision) { response, raw ->
                checkErrorHeader(response)
                JmCheckInResponseParser.parseAwardYears(raw)
            }
        }

        suspend fun fetchMonthlyAwards(uid: String, year: String, sessionRevision: Long): List<JmMonthlyAward> = withContext(Dispatchers.IO) {
            verifySession(uid, sessionRevision)
            val base = JmRouteManager.baseUrl.trimEnd('/')
            if (base.isBlank()) throw IOException("JM 暂无可用线路")
            val request = Request.Builder().url(base.toHttpUrl().newBuilder().addPathSegments("daily_list/filter").build())
                .post(multipart("data" to year)).jmHeaders().build()
            val rows = executeRead(request, uid, sessionRevision) { response, raw ->
                checkErrorHeader(response)
                JmCheckInResponseParser.parseMonthlyAwards(raw, year)
            }
            val imageHost = if (rows.any { JmCheckInResponseParser.requiresImageHost(it.imagePath) }) {
                fetchImageHost(uid, sessionRevision)
            } else {
                null
            }
            rows.map { it.copy(imageUrl = JmCheckInResponseParser.joinImageUrl(imageHost, it.imagePath)) }
        }

        private suspend fun fetchImageHost(uid: String, sessionRevision: Long): String? = withContext(Dispatchers.IO) {
            verifySession(uid, sessionRevision)
            val base = JmRouteManager.baseUrl.trimEnd('/')
            if (base.isBlank()) return@withContext null
            val request = Request.Builder()
                .url(
                    base.toHttpUrl().newBuilder().addPathSegment("setting").addQueryParameter("app_img_shunt", "1")
                        .addQueryParameter("express", "").addQueryParameter("lang", "TW").build(),
                )
                .get().jmHeaders().build()
            try {
                executeRead(request, uid, sessionRevision) { response, raw ->
                    checkErrorHeader(response)
                    JmCheckInResponseParser.parseImageHost(raw)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }

        suspend fun submitMakeup(uid: String, dailyId: String, date: LocalDate, sessionRevision: Long) = withContext(Dispatchers.IO) {
            verifySession(uid, sessionRevision)
            val base = JmRouteManager.baseUrl.trimEnd('/')
            if (base.isBlank()) throw IOException("JM 暂无可用线路")
            val request = Request.Builder().url(base.toHttpUrl().newBuilder().addPathSegment("daily_chk").build())
                .post(multipart("user_id" to uid, "daily_id" to dailyId, "date" to date.toString())).jmHeaders().build()
            // A write is never retried automatically: a timeout may have committed server-side.
            withAccount(uid) {
                if (JmAuthStore.sessionRevision != sessionRevision) throw IOException("JM 登录状态已变化，请重新加载")
                execute(request) { response, raw ->
                    checkErrorHeader(response)
                    JmCheckInResponseParser.parseMakeupResponse(raw)
                }
            }
            verifySession(uid, sessionRevision)
        }

        private suspend fun <T> executeRead(request: Request, uid: String, revision: Long, parse: (okhttp3.Response, String) -> T): T {
            return try {
                verifySession(uid, revision)
                val result = withAccount(uid) { execute(request, parse) }
                verifySession(uid, revision)
                result
            } catch (error: IOException) {
                val message = error.message.orEmpty()
                val retryableReadFailure = error is JmInvalidJsonResponseException ||
                    Regex("HTTP (429|5\\d{2})").containsMatchIn(message)
                if (!retryableReadFailure) throw error
                delay(9_000L)
                verifySession(uid, revision)
                val result = withAccount(uid) { execute(request, parse) }
                verifySession(uid, revision)
                result
            }
        }

        private fun verifySession(uid: String, revision: Long) {
            verifyAccount(uid)
            if (JmAuthStore.sessionRevision != revision) throw IOException("JM 登录状态已变化，请重新加载")
        }

        private fun multipart(vararg fields: Pair<String, String>): okhttp3.RequestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply { fields.forEach { (name, value) -> addFormDataPart(name, value) } }
            .build()

        override suspend fun fetchDaily(uid: String): JmDailySnapshot = requestDaily(uid)

        override suspend fun submit(uid: String, dailyId: String) {
            withContext(Dispatchers.IO) {
                verifyAccount(uid)
                withAccount(uid) {
                    val base = JmRouteManager.baseUrl.trimEnd('/')
                    if (base.isBlank()) throw IOException("JM 暂无可用线路")
                    val request = Request.Builder()
                        .url(base.toHttpUrl().newBuilder().addPathSegment("daily_chk").build())
                        .post(FormBody.Builder().add("user_id", uid).add("daily_id", dailyId).build())
                        .jmHeaders()
                        .build()
                    execute(request) { response, raw ->
                        checkErrorHeader(response)
                        JmCheckInResponseParser.parseSubmitResponse(raw)
                        // The confirmed endpoint normally returns data:[] with no status/message.
                        Unit
                    }
                }
            }
        }

        private suspend fun requestDaily(uid: String): JmDailySnapshot = withContext(Dispatchers.IO) {
            verifyAccount(uid)
            withAccount(uid) {
                val base = JmRouteManager.baseUrl.trimEnd('/')
                if (base.isBlank()) throw IOException("JM 暂无可用线路")
                val url = base.toHttpUrl().newBuilder()
                    .addPathSegment("daily")
                    .addQueryParameter("user_id", uid)
                    .build()
                val request = Request.Builder().url(url).get().jmHeaders().build()
                execute(request) { response, raw ->
                    checkErrorHeader(response)
                    JmCheckInResponseParser.parseDailySnapshot(raw, LocalDate.now())
                }
            }
        }

        private fun <T> withAccount(uid: String, block: () -> T): T =
            JmAuthStore.withAccount(uid, block) ?: throw IOException("JM 账号已切换，请重新发起签到")

        private fun <T> execute(request: Request, parse: (okhttp3.Response, String) -> T): T {
            try {
                JmNetworkModule.client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IOException("签到请求失败（HTTP ${response.code}）")
                    return parse(response, raw)
                }
            } catch (error: IOException) {
                // Do not pass network exception messages through; they may include request URLs/query parameters.
                if (error.message?.startsWith("签到") == true) throw error
                throw IOException("签到请求失败，请检查网络后重试")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                throw IOException("签到响应无效，请稍后重试")
            }
        }

        private fun Request.Builder.jmHeaders(): Request.Builder = this
            .header("User-Agent", JmApiInterceptor.USER_AGENT)
            .header("Origin", JmApiInterceptor.ORIGIN)
            .header("Referer", JmApiInterceptor.REFERER)
            .header("X-Requested-With", JmApiInterceptor.PACKAGE_NAME)

        private fun checkErrorHeader(response: okhttp3.Response) {
            if (!response.header("X-JM-Error-Msg").isNullOrBlank()) {
                throw IOException("签到失败：服务端拒绝了签到请求")
            }
        }
    }
}

/** Pure response parsing keeps server status and daily read-back policy directly testable. */
internal object JmCheckInResponseParser {
    fun parseSubmitResponse(raw: String) {
        if (runCatching { JSONArray(raw) }.getOrNull() != null) return
        checkApiError(parseJson(raw))
    }

    fun parseDailySnapshot(raw: String, today: LocalDate): JmDailySnapshot {
        val root = parseJson(raw)
        checkApiError(root)
        val data = root.optJSONObject("data") ?: root
        val dailyId = data.optString("daily_id").takeIf { it.isNotBlank() }
            ?: throw IOException("签到信息缺少有效 daily_id")
        return JmDailySnapshot(dailyId, hasSignedToday(data.optJSONArray("record"), today))
    }

    fun parseCalendar(raw: String, today: LocalDate, accountHash: String, sessionRevision: Long): JmDailyCalendar {
        val root = parseJson(raw)
        checkApiError(root)
        val data = root.optJSONObject("data") ?: root
        val dailyId = data.optString("daily_id").takeIf(::isPositiveInteger)
            ?: throw IOException("签到信息缺少有效 daily_id")
        val month = YearMonth.from(today)
        val days = mutableMapOf<LocalDate, JmCalendarDay>()
        val weeks = data.optJSONArray("record") ?: throw IOException("签到日历缺少 record")
        for (weekIndex in 0 until weeks.length()) {
            val week = weeks.optJSONArray(weekIndex) ?: continue
            for (dayIndex in 0 until week.length()) {
                val item = week.optJSONObject(dayIndex) ?: continue
                val dayValue = item.optString("date").trim()
                val numericDay = dayValue.toIntOrNull()
                val date = when {
                    dayValue.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> runCatching { LocalDate.parse(dayValue) }.getOrNull()
                    else -> numericDay?.takeIf { it in 1..month.lengthOfMonth() }?.let(month::atDay)
                } ?: continue
                if (YearMonth.from(date) != month) continue
                val signed = item.opt("signed") as? Boolean
                days[date] = JmCalendarDay(date, signed, item.optBoolean("bonus", false))
            }
        }
        if (days.isEmpty()) throw IOException("签到日历没有有效日期")
        fun field(name: String) = data.optString(name).takeIf { it.isNotBlank() }
        return JmDailyCalendar(
            dailyId = dailyId,
            month = month,
            days = days.values.sortedBy { it.date },
            eventName = field("event_name"),
            progress = field("currentProgress"),
            threeDayCoin = field("three_days_coin"),
            threeDayExp = field("three_days_exp"),
            sevenDayCoin = field("seven_days_coin"),
            sevenDayExp = field("seven_days_exp"),
            accountHash = accountHash,
            sessionRevision = sessionRevision,
        )
    }

    fun parseAwardYears(raw: String): List<String> {
        val root = parseJson(raw)
        checkApiError(root)
        val data = root.optJSONObject("data") ?: root
        val list = data.optJSONArray("list") ?: root.optJSONArray("list") ?: JSONArray()
        return (0 until list.length()).mapNotNull { index ->
            list.optJSONObject(index)?.optString("title")?.takeIf(::isValidAwardYear)
        }.distinct().sortedDescending()
    }

    fun parseMonthlyAwards(raw: String, requestedYear: String): List<JmMonthlyAward> {
        if (!isValidAwardYear(requestedYear)) throw IOException("年份无效")
        val root = parseJson(raw)
        checkApiError(root)
        val data = root.optJSONObject("data") ?: root
        val list = data.optJSONArray("list") ?: root.optJSONArray("list") ?: JSONArray()
        val year = requestedYear.toInt()
        return (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            val rowYear = item.optString("year").toIntOrNull() ?: return@mapNotNull null
            val month = item.optString("month").toIntOrNull() ?: return@mapNotNull null
            val id = item.optString("id").takeIf(::isPositiveInteger) ?: return@mapNotNull null
            if (rowYear != year || month !in 1..12) return@mapNotNull null
            val image = if (item.isNull("img")) null else item.optString("img").takeIf { it.isNotBlank() && it.trim() != "null" }
            JmMonthlyAward(id, rowYear, month, image, null)
        }.sortedBy { it.month }
    }

    fun parseImageHost(raw: String): String? {
        val root = parseJson(raw)
        checkApiError(root)
        val data = root.optJSONObject("data") ?: root
        val host = data.optString("img_host").trim().trimEnd('/').takeIf { it.isNotEmpty() } ?: return null
        val parsed = runCatching { host.toHttpUrl() }.getOrNull() ?: return null
        return host.takeIf { parsed.isHttps && parsed.host.isNotBlank() }
    }

    fun joinImageUrl(imageHost: String?, imagePath: String?): String? {
        val path = imagePath?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        if (path.startsWith("//")) {
            return secureHttpsUrl("https:$path")
        }
        if (URL_SCHEME.containsMatchIn(path)) {
            return secureHttpsUrl(path)
        }

        val host = imageHost?.takeIf { it.isNotBlank() } ?: return null
        val parsedHost = runCatching { host.toHttpUrl() }.getOrNull()?.takeIf(::isSecureHttpsUrl) ?: return null
        return runCatching { parsedHost.resolve(path) }.getOrNull()?.takeIf(::isSecureHttpsUrl)?.toString()
    }

    /** Only relative artwork requires the optional `/setting` image host lookup. */
    fun requiresImageHost(imagePath: String?): Boolean {
        val path = imagePath?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return false
        return !path.startsWith("//") && !URL_SCHEME.containsMatchIn(path)
    }

    private fun secureHttpsUrl(value: String): String? =
        runCatching { value.toHttpUrl() }.getOrNull()?.takeIf(::isSecureHttpsUrl)?.toString()

    private fun isSecureHttpsUrl(url: okhttp3.HttpUrl): Boolean =
        url.isHttps && url.host.isNotBlank() && url.username.isEmpty() && url.password.isEmpty()

    private val URL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun parseMakeupResponse(raw: String) {
        val root = parseJson(raw)
        checkApiError(root)
        val status = root.optString("status").trim().lowercase()
        val message = root.optString("msg").trim()
        val lower = message.lowercase()
        if (lower.contains("只能補簽") || lower.contains("隻能補簽") || lower.contains("只能补签") || lower.contains("失败") || lower.contains("失敗")) {
            throw IOException("补签被 JM 拒绝")
        }
        if (status in setOf("success", "ok") || lower.contains("補簽成功") || lower.contains("补签成功") || lower.contains("success")) return
        throw IOException("补签响应未能确认结果")
    }

    private fun parseJson(raw: String): JSONObject = runCatching { JSONObject(raw) }
        .getOrElse { throw JmInvalidJsonResponseException() }

    private fun isPositiveInteger(value: String): Boolean =
        value.isNotEmpty() && value.all { it in '0'..'9' } && value.any { it != '0' }

    private fun checkApiError(root: JSONObject) {
        val status = root.optString("status").trim().lowercase()
        val error = if (root.isNull("errorMsg")) "" else root.optString("errorMsg").trim()
        if (status in setOf("fail", "failed", "error") || error.isNotEmpty()) {
            throw IOException("签到失败：服务端拒绝了签到请求")
        }
    }

    private fun hasSignedToday(record: JSONArray?, today: LocalDate): Boolean {
        if (record == null) return false
        for (weekIndex in 0 until record.length()) {
            val week = record.optJSONArray(weekIndex) ?: continue
            for (dayIndex in 0 until week.length()) {
                val day = week.optJSONObject(dayIndex) ?: continue
                val date = day.optString("date").trim()
                val isToday = date == today.toString() || date.toIntOrNull() == today.dayOfMonth
                if (isToday && day.opt("signed") == true) return true
            }
        }
        return false
    }
}

internal data class JmCalendarDay(val date: LocalDate, val signed: Boolean?, val bonus: Boolean)

internal data class JmDailyCalendar(
    val dailyId: String,
    val month: YearMonth,
    val days: List<JmCalendarDay>,
    val eventName: String?,
    val progress: String?,
    val threeDayCoin: String?,
    val threeDayExp: String?,
    val sevenDayCoin: String?,
    val sevenDayExp: String?,
    val accountHash: String,
    val sessionRevision: Long,
) {
    fun makeupCandidates(today: LocalDate): List<JmCalendarDay> = days
        .filter { it.date.year == month.year && it.date.monthValue == month.monthValue && it.date < today && it.signed != true }
        .sortedBy { it.date }
}

internal data class JmMonthlyAward(val id: String, val year: Int, val month: Int, val imagePath: String?, val imageUrl: String?)
internal data class JmMakeupProgress(val completed: Int, val total: Int, val date: LocalDate, val error: String?)
internal data class JmMakeupResult(val completed: List<LocalDate>, val failedDate: LocalDate?, val error: String?)

internal class JmInvalidJsonResponseException : IOException("签到响应无效，请稍后重试")

internal fun isValidAwardYear(value: String): Boolean =
    value.matches(Regex("[1-9]\\d{3}")) && value.toIntOrNull()?.let { it >= 1000 } == true

/** Small injectable seam for deterministic repository policy tests. */
internal data class JmDailySnapshot(val dailyId: String, val signedToday: Boolean)

internal interface JmCheckInApi {
    suspend fun fetchDaily(uid: String): JmDailySnapshot
    suspend fun submit(uid: String, dailyId: String)
}

internal interface JmCheckInState {
    fun isAutomaticEnabled(): Boolean
    fun attemptedDate(accountHash: String): String?
    fun successfulDate(accountHash: String): String?
    fun markAttempt(accountHash: String, date: String)
    fun markSuccess(accountHash: String, date: String)
    fun currentAccountHash(): String?
}

/** Pure local UI-state policy, deliberately independent of the network and Android storage. */
internal object JmCheckInUiState {
    fun restoredMessage(accountHash: String?, today: String, successfulDateForAccount: (String) -> String?): String? {
        val successfulDate = accountHash?.let(successfulDateForAccount) ?: return null
        return if (successfulDate == today) "今日已签到" else null
    }
}

internal class JmCheckInEngine(
    private val backend: JmCheckInApi,
    private val state: JmCheckInState,
    private val mutex: Mutex = Mutex(),
) {
    suspend fun checkIn(uid: String, accountHash: String, today: String, automatic: Boolean): String? = mutex.withLock {
        if (automatic && !state.isAutomaticEnabled()) return@withLock null
        if (state.currentAccountHash() != accountHash) throw IOException("JM 账号已切换，请重新发起签到")
        if (state.successfulDate(accountHash) == today) return@withLock if (automatic) null else "今日已签到"
        if (automatic && state.attemptedDate(accountHash) == today) return@withLock null
        if (automatic) state.markAttempt(accountHash, today)

        fun verifySession() {
            if (state.currentAccountHash() != accountHash) throw IOException("JM 账号已切换，请重新发起签到")
        }
        verifySession()
        val before = backend.fetchDaily(uid)
        verifySession()
        if (before.signedToday) {
            state.markSuccess(accountHash, today)
            return@withLock "今日已签到"
        }
        if (before.dailyId.isBlank()) throw IOException("签到信息无效，请稍后重试")
        backend.submit(uid, before.dailyId)
        verifySession()
        val after = backend.fetchDaily(uid)
        verifySession()
        if (!after.signedToday) throw IOException("签到未能确认成功，请稍后重试")
        state.markSuccess(accountHash, today)
        "签到成功"
    }
}
