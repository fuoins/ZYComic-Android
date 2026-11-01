package com.zycomic.app.net

/**
 * Persistence scope for everything measured through the network stack.
 *
 * Direct transport (RuleDns + per-IP pinning) and system-VPN transport (system DNS, hostname only)
 * produce incomparable measurements, so their snapshots, history and selections must never be
 * written to or read from the same bucket.
 */
enum class NetworkScope(val storageKey: String) {
    DIRECT("direct"),
    VPN("vpn"),
    ;

    /** User-facing name for dialogs and status reports. */
    val displayName: String get() = if (this == VPN) "VPN" else "直连"

    companion object {
        /** The scope of the transport that is actually in use right now. */
        fun current(): NetworkScope = if (NetworkModule.shouldUseDirectStrategy()) DIRECT else VPN

        /** The scope a persisted measurement belongs to. */
        fun of(path: SpeedTestNetworkPath?): NetworkScope =
            if (path != null && path.followSystemVpnEnabled && path.systemVpnActive) VPN else DIRECT

        /**
         * Decode [PlatformSpeedTestCoordinator.currentNetworkIdentity], whose third field is the
         * direct-strategy flag (`...|underlying=...|true|true`).
         */
        fun fromNetworkIdentity(identity: String?): NetworkScope? {
            val direct = identity?.split('|')?.getOrNull(2)?.toBooleanStrictOrNull() ?: return null
            return if (direct) DIRECT else VPN
        }
    }
}
