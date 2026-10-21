package com.zycomic.app.net

/** Display-only counts; historical successes must never inflate this round's result. */
class SpeedTestLogSummary(private val results: List<Result>) {
    data class Result(val testedThisRound: Boolean, val successful: Boolean, val quickTest: Boolean)

    fun text(): String {
        val fresh = results.filter { it.testedThisRound }
        val historical = results.size - fresh.size
        val mode = when {
            fresh.any { it.quickTest } -> "本轮快测"
            fresh.isNotEmpty() -> "本轮测速"
            else -> "本轮未测"
        }
        return "$mode：本轮成功 ${fresh.count { it.successful }}/${fresh.size}；" +
            "历史沿用 $historical 条（本轮未测）"
    }
}
