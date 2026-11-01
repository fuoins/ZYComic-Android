package com.zycomic.app.net

import java.util.Calendar

/**
 * Time-of-day bucket for DIRECT speed-test records.
 *
 * Mobile carriers and home broadband are visibly faster/slower in different hours, so a route
 * selected at noon is not necessarily the best route at midnight. Direct measurements, selections
 * and snapshots are therefore split into a day bucket (06:00-18:00) and a night bucket
 * (18:00-06:00). VPN traffic is measured hostname-only through the system resolver, so its results
 * stay in one bucket and never enter either time slot.
 */
enum class SpeedTestTimeSlot(val storageKey: String, val displayName: String) {
    DAY("day", "06:00-18:00"),
    NIGHT("night", "18:00-06:00"),
    ;

    companion object {
        fun current(now: Long = System.currentTimeMillis()): SpeedTestTimeSlot {
            val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
            return if (hour in DAY_START_HOUR until NIGHT_START_HOUR) DAY else NIGHT
        }

        /** Milliseconds until the next 06:00/18:00 boundary, at least one second. */
        fun millisUntilNextBoundary(now: Long = System.currentTimeMillis()): Long {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                val hour = get(Calendar.HOUR_OF_DAY)
                when {
                    hour < DAY_START_HOUR -> set(Calendar.HOUR_OF_DAY, DAY_START_HOUR)
                    hour < NIGHT_START_HOUR -> set(Calendar.HOUR_OF_DAY, NIGHT_START_HOUR)
                    else -> {
                        add(Calendar.DAY_OF_MONTH, 1)
                        set(Calendar.HOUR_OF_DAY, DAY_START_HOUR)
                    }
                }
            }
            return (calendar.timeInMillis - now).coerceAtLeast(1_000L)
        }

        private const val DAY_START_HOUR = 6
        private const val NIGHT_START_HOUR = 18
    }
}

/** Namespacing helper shared by every persisted speed-test bucket. */
internal object SpeedTestStorage {
    const val TIME_SLOT_MIGRATED_KEY = "speed_test_time_slot_storage_v1"

    /**
     * Direct storage moves between the day/night keys; VPN keeps its historical single bucket
     * because the requirement (and its transport semantics) only splits direct measurements.
     */
    fun bucketKey(scope: NetworkScope, slot: SpeedTestTimeSlot = SpeedTestTimeSlot.current()): String =
        if (scope == NetworkScope.VPN) scope.storageKey else "${scope.storageKey}_${slot.storageKey}"

    fun displayName(scope: NetworkScope, slot: SpeedTestTimeSlot): String =
        if (scope == NetworkScope.VPN) scope.displayName else "${scope.displayName} · ${slot.displayName}"
}
