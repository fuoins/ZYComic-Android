package com.zycomic.app.net

/** Process-local only: a new process still performs its normal startup quick/full test. */
internal class CompletedSpeedTestPhases {
    data class Key(
        val remoteVersion: Int,
        val routingRevision: Long,
        val platformRevision: Long,
        val networkIdentity: String,
        val platform: String,
    )

    enum class Phase { LINES, IMAGES }

    private val completed = mutableMapOf<Pair<String, Phase>, Key>()

    @Synchronized
    fun contains(key: Key, phase: Phase): Boolean = completed[key.platform to phase] == key

    @Synchronized
    fun record(key: Key, phase: Phase) {
        completed[key.platform to phase] = key
    }

    @Synchronized
    fun invalidate(platform: String) {
        Phase.entries.forEach { completed.remove(platform to it) }
    }

    fun canReuse(key: Key, phase: Phase, forceFresh: Boolean, platformOnly: Boolean): Boolean =
        !forceFresh && !platformOnly && contains(key, phase)
}
