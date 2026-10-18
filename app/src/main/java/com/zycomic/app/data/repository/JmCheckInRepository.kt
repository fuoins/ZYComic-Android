package com.zycomic.app.data.repository

import android.content.Context
import com.zycomic.app.net.JmApiInterceptor
import com.zycomic.app.net.JmAuthStore
import com.zycomic.app.net.JmNetworkModule
import com.zycomic.app.net.JmRouteManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate

/** JM check-in requests are isolated from the Manwa API and never persist a raw account ID. */
object JmCheckInRepository {
    private const val PREFS_NAME = "jm_check_in"
    private const val KEY_AUTO_ENABLED = "auto_enabled"
    private const val KEY_ATTEMPT_PREFIX = "attempt_"
    private const val KEY_SUCCESS_PREFIX = "success_"
    private val mutex = Mutex()

    /** UI preference accessor; automatic check-ins are enabled for existing installs by default. */
    fun isAutoEnabled(context: Context): Boolean = preferences(context).getBoolean(KEY_AUTO_ENABLED, true)

    fun setAutoEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply()
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

    private fun parseJson(raw: String): JSONObject = runCatching { JSONObject(raw) }
        .getOrElse { throw IOException("签到响应无效，请稍后重试") }

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
