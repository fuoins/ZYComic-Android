package com.zycomic.app.net

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/** SmartDNS is refreshed by configuration checks, never by probes or image requests. */
internal object PicacgNetworkGeneration {
    private const val PREFS = "zycomic_picacg_network_generation"
    private val lock = Mutex()
    private var checked = false
    private var attemptedVersion: Int? = null
    private var candidates: List<String> = emptyList()

    private fun prefs() = DevConfig.appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Restore only this installation's current generation; no network I/O during initialization. */
    fun restore(version: Int?) {
        val prefs = prefs()
        checked = prefs?.getBoolean("checked", false) == true
        attemptedVersion = prefs?.getInt("version", -1)?.takeIf { it >= 0 }
        candidates = if (attemptedVersion == version) {
            runCatching {
                val array = JSONArray(prefs?.getString("ips", "[]") ?: "[]")
                (0 until array.length()).map { array.getString(it) }.filter(::isPublicIpv4).distinct()
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        PicacgRouteManager.installBuiltin(version, candidates)
    }

    /** First check (even offline), or a successfully applied higher version, gets exactly one attempt. */
    suspend fun onConfigCheck(appliedVersion: Int? = null, fetch: suspend () -> List<String> = PicacgSmartDns::fetch) = lock.withLock {
        if (!shouldFetch(checked, attemptedVersion, appliedVersion)) return@withLock
        checked = true
        attemptedVersion = appliedVersion ?: PicacgRouteManager.remoteVersion
        candidates = emptyList()
        // Record the attempt before I/O, so a failed fetch/process restart cannot cause a same-version retry loop.
        persist()
        candidates = try {
            fetch().filter(::isPublicIpv4).distinct()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        persist()
        PicacgRouteManager.installBuiltin(attemptedVersion, candidates)
    }

    internal fun shouldFetch(checked: Boolean, attemptedVersion: Int?, appliedVersion: Int?): Boolean =
        !checked || (appliedVersion != null && (attemptedVersion == null || appliedVersion > attemptedVersion))

    private fun persist() {
        prefs()?.edit()?.putBoolean("checked", checked)
            ?.putInt("version", attemptedVersion ?: -1)
            ?.putString("ips", JSONArray(candidates).toString())?.commit()
    }
}
