package com.zycomic.app.ui.settings

import com.zycomic.app.net.DomainIp
import java.util.Locale

/** Pure ordering helpers shared by the JM speed-test screen and its exported log. */
internal object JmSpeedTestLogPolicy {
    fun rankEndpoints(
        delays: Map<DomainIp, Long>,
        failedEndpoints: Set<DomainIp> = emptySet(),
    ): List<DomainIp> = delays.keys.sortedWith(
        compareBy<DomainIp>(
            { it in failedEndpoints || delays.getValue(it) < 0L || delays.getValue(it) == Long.MAX_VALUE },
            { delays.getValue(it) },
            { it.domain.lowercase(Locale.ROOT) },
            { it.ip.lowercase(Locale.ROOT) },
        ),
    )

    fun rankHosts(delays: Map<String, Long>): List<String> = delays.keys.sortedWith(
        compareBy<String>(
            { delays.getValue(it) < 0L || delays.getValue(it) == Long.MAX_VALUE },
            { delays.getValue(it) },
            { it.lowercase(Locale.ROOT) },
        ),
    )
}
