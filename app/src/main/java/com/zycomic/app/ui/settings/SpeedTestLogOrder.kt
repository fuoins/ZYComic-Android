package com.zycomic.app.ui.settings

import java.util.Locale

/** Rank only this round's measurements; retain the published order of historical results. */
internal fun <T> orderSpeedTestLogRows(
    rows: List<T>,
    testedThisRound: (T) -> Boolean,
    successful: (T) -> Boolean,
    elapsedMs: (T) -> Long,
    domain: (T) -> String,
    ip: (T) -> String,
): List<T> {
    val (fresh, historical) = rows.partition(testedThisRound)
    return fresh.sortedWith(
        compareBy<T>(
            { !successful(it) },
            { if (successful(it)) elapsedMs(it) else Long.MAX_VALUE },
            { domain(it).lowercase(Locale.ROOT) },
            { ip(it).lowercase(Locale.ROOT) },
        ),
    ) + historical
}
