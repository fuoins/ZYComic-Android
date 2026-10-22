package com.zycomic.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR
import java.util.concurrent.atomic.AtomicLong

/** The OS is authoritative: a connected Wi-Fi/VPN alone is not validated internet. */
internal object RuntimeNetworkGate {
    @Volatile private var context: Context? = null
    private val generation = AtomicLong()
    val revision: Long get() = generation.get()

    @Volatile private var lastValidated: Boolean? = null

    @Synchronized
    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        val manager = this.context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    updateValidation(
                        RuntimeNetworkPolicy.mayRecover(
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        ),
                    )
                }

                override fun onLost(network: Network) {
                    updateValidation(false)
                }
            })
        }
    }

    fun isOnline(): Boolean = runCatching {
        val manager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return@runCatching false
        RuntimeNetworkPolicy.mayRecover(
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }.getOrDefault(false).also(::updateValidation)

    @Synchronized
    private fun updateValidation(validated: Boolean) {
        if (lastValidated != validated) {
            lastValidated = validated
            generation.incrementAndGet()
        }
    }

    fun failureMessage(): String {
        val resource = if (isOnline()) {
            KMR.strings.reader_suspected_endpoint_failure
        } else {
            KMR.strings.reader_network_offline_failure
        }
        return context?.stringResource(resource).orEmpty()
    }

    fun ambiguousWriteMessage(): String = context?.stringResource(KMR.strings.network_write_outcome_uncertain)
        ?: "Write may have succeeded; refresh to confirm"
}
