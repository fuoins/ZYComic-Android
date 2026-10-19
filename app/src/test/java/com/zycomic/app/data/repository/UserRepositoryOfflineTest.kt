package com.zycomic.app.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.zycomic.app.data.dto.ApiResponse
import com.zycomic.app.data.dto.User
import com.zycomic.app.net.ApiService
import com.zycomic.app.net.NetworkModule
import com.zycomic.app.net.RouteManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.CancellationException

class UserRepositoryOfflineTest {

    @Test
    fun `cached session semantics refresh fencing and logout cancellation`() = runBlocking {
        val context = InMemoryContext()
        val api = OfflineApi()
        NetworkModule.init(context)
        UserRepository.init(context)
        UserRepository.setApiForTesting(api.proxy())
        RouteManager.lineHosts = listOf("https://example.test")

        try {
            val prefs = context.getSharedPreferences("zycomic_user", Context.MODE_PRIVATE)
            val cached = User(uid = "cached", nickname = "cached profile")
            prefs.edit().putString(USER_INFO_KEY, serialize(User(uid = ""))).apply()
            assertFalse(UserRepository.restoreLoginFromLocal(), "invalid uid must not populate userFlow")
            prefs.edit().putString(USER_INFO_KEY, serialize(User(uid = "0"))).apply()
            assertFalse(UserRepository.restoreLoginFromLocal(), "sentinel uid must not populate userFlow")
            assertNull(UserRepository.userFlow.value)
            assertEquals(0, api.userInfoCalls, "local restore must never perform network I/O")

            prefs.edit().putString(USER_INFO_KEY, serialize(cached)).apply()
            assertTrue(UserRepository.restoreLoginFromLocal())
            assertEquals(cached, UserRepository.userFlow.value)
            assertTrue(UserRepository.isLoggedIn, "cached profile presence preserves app gate semantics")
            assertFalse(UserRepository.isSessionVerified)
            assertEquals(ManwaSessionStatus.UNKNOWN, UserRepository.sessionStatusFlow.value)
            assertEquals(0, api.userInfoCalls)

            val cookies = NetworkModule.cookieJar
            cookies.add("uid", "cached", "example.test")
            val unknownResponses = listOf(0, -1, 401)
            for (code in unknownResponses) {
                api.userInfoResults.add(ApiResponse(code = code, data = cached))
                assertThrows(IOException::class.java) { runBlocking { UserRepository.refreshUserInfo() } }
                assertEquals(cached, UserRepository.userFlow.value)
                assertTrue(UserRepository.isLoggedIn)
                assertFalse(UserRepository.isSessionVerified)
                assertTrue(prefs.getString(USER_INFO_KEY, null) != null)
                assertEquals("cached", cookies.getAll().single().value)
            }

            api.userInfoResults.add(
                HttpException(
                    Response.error<ApiResponse<User>>(
                        403,
                        "Forbidden".toResponseBody(),
                    ),
                ),
            )
            assertThrows(HttpException::class.java) { runBlocking { UserRepository.refreshUserInfo() } }
            assertEquals(cached, UserRepository.userFlow.value)
            assertEquals("cached", cookies.getAll().single().value)

            api.userInfoResults.add(ApiResponse<User>(code = 1, data = null))
            assertThrows(IOException::class.java) { runBlocking { UserRepository.refreshUserInfo() } }
            assertEquals(cached, UserRepository.userFlow.value)
            assertEquals("cached", cookies.getAll().single().value)

            api.userInfoResults.add(IOException("offline"))
            assertThrows(IOException::class.java) { runBlocking { UserRepository.refreshUserInfo() } }
            assertEquals(cached, UserRepository.userFlow.value)
            assertEquals("cached", cookies.getAll().single().value)

            api.userInfoResults.add(java.net.SocketTimeoutException("timeout"))
            assertThrows(java.net.SocketTimeoutException::class.java) { runBlocking { UserRepository.refreshUserInfo() } }
            api.userInfoResults.add(IllegalStateException("malformed profile"))
            assertThrows(IllegalStateException::class.java) { runBlocking { UserRepository.refreshUserInfo() } }
            assertEquals(cached, UserRepository.userFlow.value)
            assertEquals("cached", cookies.getAll().single().value)

            api.userInfoResults.add(CancellationException("refresh cancelled"))
            assertThrows(CancellationException::class.java) { runBlocking { UserRepository.refreshUserInfo() } }
            assertEquals(cached, UserRepository.userFlow.value)
            assertEquals("cached", cookies.getAll().single().value)
            assertFalse(UserRepository.isSessionVerified)

            val refreshed = User(uid = "cached", nickname = "fresh profile")
            api.userInfoResults.add(ApiResponse(code = 1, data = refreshed))
            UserRepository.refreshUserInfo()
            assertEquals(refreshed, UserRepository.userFlow.value)
            assertTrue(UserRepository.isSessionVerified)
            assertEquals(refreshed, deserialize(prefs.getString(USER_INFO_KEY, null)))

            api.userInfoResults.add(
                HttpException(
                    Response.error<ApiResponse<User>>(
                        401,
                        "Unauthorized".toResponseBody(),
                    ),
                ),
            )
            UserRepository.refreshUserInfo()
            assertNull(UserRepository.userFlow.value)
            assertFalse(UserRepository.isLoggedIn)
            assertEquals(ManwaSessionStatus.EXPIRED, UserRepository.sessionStatusFlow.value)
            assertNull(prefs.getString(USER_INFO_KEY, null))
            assertTrue(cookies.getAll().isEmpty())

            prefs.edit().putString(USER_INFO_KEY, serialize(cached)).apply()
            assertTrue(UserRepository.restoreLoginFromLocal())
            api.deferNextUserInfo = true
            val oldRefresh = async(start = CoroutineStart.UNDISPATCHED) { UserRepository.refreshUserInfo() }
            api.deferredUserInfoStarted.await()

            val newProfile = User(uid = "new-account", nickname = "new")
            api.loginResponse = ApiResponse(code = 1, data = User(uid = "new-account"))
            api.userInfoResults.add(ApiResponse(code = 1, data = newProfile))
            assertEquals(newProfile, UserRepository.login("new", "password"))
            assertTrue(UserRepository.isSessionVerified)

            api.deferredUserInfo!!.resumeWith(Result.success(ApiResponse(code = 1, data = cached)))
            assertThrows(CancellationException::class.java) { runBlocking { oldRefresh.await() } }
            assertEquals(newProfile, UserRepository.userFlow.value, "a stale response must not replace the newer account")
            assertEquals(ManwaSessionStatus.VERIFIED, UserRepository.sessionStatusFlow.value)
            assertEquals(newProfile, deserialize(prefs.getString(USER_INFO_KEY, null)))

            api.deferNextUserInfo = true
            val oldUnauthorized = async(start = CoroutineStart.UNDISPATCHED) { UserRepository.refreshUserInfo() }
            api.userInfoResults.add(ApiResponse(code = 1, data = newProfile))
            UserRepository.login("new", "password")
            api.deferredUserInfo!!.resumeWith(
                Result.failure(HttpException(Response.error<ApiResponse<User>>(401, "Unauthorized".toResponseBody()))),
            )
            assertThrows(CancellationException::class.java) { runBlocking { oldUnauthorized.await() } }
            assertEquals(newProfile, UserRepository.userFlow.value, "old HTTP 401 must not clear a new login")
            assertEquals(ManwaSessionStatus.VERIFIED, UserRepository.sessionStatusFlow.value)

            api.cancelLogout = true
            assertThrows(CancellationException::class.java) { runBlocking { UserRepository.logout() } }
            assertNull(UserRepository.userFlow.value, "logout must clear local state before cancellable remote work")
            assertFalse(UserRepository.isLoggedIn)
            assertEquals(ManwaSessionStatus.SIGNED_OUT, UserRepository.sessionStatusFlow.value)
            assertNull(prefs.getString(USER_INFO_KEY, null))
            assertTrue(cookies.getAll().isEmpty())
        } finally {
            UserRepository.setApiForTesting(null)
        }
    }

    private class OfflineApi {
        val userInfoResults = ArrayDeque<Any>()
        var userInfoCalls = 0
        var loginResponse: ApiResponse<User> = ApiResponse()
        var deferNextUserInfo = false
        var deferredUserInfo: kotlin.coroutines.Continuation<Any?>? = null
        val deferredUserInfoStarted = CompletableDeferred<Unit>()
        var cancelLogout = false

        fun proxy(): ApiService = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "userInfo" -> {
                    userInfoCalls++
                    if (deferNextUserInfo) {
                        deferNextUserInfo = false
                        deferredUserInfo = args?.last() as kotlin.coroutines.Continuation<Any?>
                        deferredUserInfoStarted.complete(Unit)
                        kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
                    } else {
                        val result = userInfoResults.removeFirst()
                        if (result is IOException || result is CancellationException) {
                            @Suppress("UNCHECKED_CAST")
                            val continuation = args?.last() as kotlin.coroutines.Continuation<Any?>
                            continuation.resumeWith(Result.failure(result))
                            return@newProxyInstance kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
                        }
                        if (result is Throwable) throw result
                        result
                    }
                }
                "login" -> loginResponse
                "logout" -> if (cancelLogout) throw CancellationException("test cancellation") else ApiResponse<Any?>()
                "toString" -> "OfflineApi"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected API call in offline test: ${method.name}")
            }
        } as ApiService
    }

    private class InMemoryContext : ContextWrapper(null) {
        private val preferences = mutableMapOf<String, MemoryPreferences>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            preferences.getOrPut(name) { MemoryPreferences() }
    }

    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = MemoryEditor(values)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class MemoryEditor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values)
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)
        override fun remove(key: String?): SharedPreferences.Editor = apply { values.remove(key) }
        override fun clear(): SharedPreferences.Editor = apply { values.clear() }
        override fun commit(): Boolean = true
        override fun apply() = Unit

        private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { values[key.orEmpty()] = value }
    }

    companion object {
        private const val USER_INFO_KEY = "user_info_json"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private fun serialize(user: User): String = json.encodeToString(User.serializer(), user)

        private fun deserialize(value: String?): User? = value?.let { json.decodeFromString(User.serializer(), it) }
    }
}
