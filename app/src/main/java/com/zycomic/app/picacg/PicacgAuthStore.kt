package com.zycomic.app.picacg

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Picacg session storage is intentionally separate from Manwa and JM auth/cookie stores. */
object PicacgAuthStore {
    private const val PREFS_NAME = "picacg_secure_tokens"
    private const val TOKEN_KEY = "auth_token"
    private const val KEY_ALIAS = "picacg_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128

    @Volatile private var preferences: android.content.SharedPreferences? = null
    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()
    private val _sessionRevision = MutableStateFlow(0L)
    val sessionRevision: StateFlow<Long> = _sessionRevision.asStateFlow()

    data class Session(val token: String?, val revision: Long)

    /** Call once from Application initialization; safe to call again with the same app context. */
    @Synchronized
    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _loggedIn.value = readToken() != null
    }

    @Synchronized
    fun token(): String? = readToken()

    @Synchronized
    fun session(): Session = Session(readToken(), _sessionRevision.value)

    @Synchronized
    fun saveToken(token: String) {
        require(token.isNotBlank()) { "Picacg token must not be blank" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
        check(requirePreferences().edit().putString(TOKEN_KEY, encoded).commit()) { "Could not persist Picacg token" }
        PicacgDetailCache.clear()
        _loggedIn.value = true
        _sessionRevision.value += 1L
    }

    /** Save a login result only if the session that initiated login is still current. */
    @Synchronized
    fun saveTokenIfRevision(token: String, expectedRevision: Long): Boolean {
        if (_sessionRevision.value != expectedRevision) return false
        saveToken(token)
        return true
    }

    @Synchronized
    fun clear() {
        requirePreferences().edit().remove(TOKEN_KEY).commit()
        PicacgDetailCache.clear()
        _loggedIn.value = false
        _sessionRevision.value += 1L
    }

    /** Clear an expired token only if no newer sign-in/logout replaced that request's session. */
    @Synchronized
    fun clearIfRevision(expectedRevision: Long): Boolean {
        if (_sessionRevision.value != expectedRevision) return false
        clear()
        return true
    }

    private fun readToken(): String? {
        val encoded = preferences?.getString(TOKEN_KEY, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_SIZE_BYTES)
            val iv = packed.copyOfRange(0, IV_SIZE_BYTES)
            val encrypted = packed.copyOfRange(IV_SIZE_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_SIZE_BITS, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8).takeIf(String::isNotBlank)
        }.getOrElse {
            clear()
            null
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun requirePreferences() = preferences ?: error("PicacgAuthStore.initialize(context) must be called first")
}
