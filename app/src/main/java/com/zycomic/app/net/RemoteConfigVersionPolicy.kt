package com.zycomic.app.net

/** Remote configuration versions are monotonic: equal or lower versions are never reapplied. */
internal object RemoteConfigVersionPolicy {
    fun shouldApply(incomingVersion: Int, appliedVersion: Int): Boolean = incomingVersion > appliedVersion
}
