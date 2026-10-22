package com.zycomic.app.reader

import android.content.Context
import com.zycomic.app.net.ImageDecryptFailedException
import com.zycomic.app.net.RuntimeNetworkGate
import com.zycomic.app.net.RuntimeRecoveryPolicy
import eu.kanade.tachiyomi.network.HttpException
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR

/** Uses connectivity state only for request failures; decode and rate-limit details remain intact. */
internal fun Context.readerFailureMessage(error: Throwable?, fallback: String): String? {
    if (error == null || error.hasCause<ImageDecryptFailedException>()) return null
    if (error.hasCause<HttpException> { it.code == 429 }) return fallback
    if (error.hasCause<HttpException>() || !RuntimeRecoveryPolicy.isTransportFailure(error)) return null
    return if (RuntimeNetworkGate.isOnline()) {
        stringResource(KMR.strings.reader_suspected_endpoint_failure)
    } else {
        stringResource(KMR.strings.reader_network_offline_failure)
    }
}

private inline fun <reified T : Throwable> Throwable.hasCause(predicate: (T) -> Boolean = { true }): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is T && predicate(cause)) return true
        cause = cause.cause
    }
    return false
}
