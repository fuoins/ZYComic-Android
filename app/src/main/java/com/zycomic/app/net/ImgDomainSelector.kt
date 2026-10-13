package com.zycomic.app.net

import java.util.concurrent.ConcurrentHashMap

/**
 * 图源选择的纯决策逻辑（不依赖 Android / 网络 / 持久化），便于单元测试。
 *
 * 优先级（已确认的设计决定）：
 * 手动 pin > 排除冷却后的派生最快 > 章节接口点名 > 列表首个
 *
 * 其中"排除冷却"有一条降级：当**所有**有测速数据的候选都在冷却时，宁可退回那个
 * 最快但已冷却的域，也不能返回"没有可用域"——否则一次网络抖动把整轮候选掏空后，
 * [ImageInterceptor] 的换域兜底也会一起失效。
 */
object ImgDomainSelector {

    /** 本轮测速判定为不可用（连不上 / 拿到的是错误页）。 */
    const val UNAVAILABLE: Long = Long.MAX_VALUE

    /** 选中理由，供日志与"恢复自动"提示用。 */
    enum class Reason { PINNED, FASTEST, FASTEST_COOLDOWN_IGNORED, CHAPTER_NAMED, FIRST_IN_LIST, NONE }

    data class Choice(val domain: String?, val reason: Reason)

    /**
     * @param candidates 当前生效的图源列表（顺序即列表顺序，用于同延迟时的确定性 tie-break）
     * @param delays 域名 -> 延迟；缺 key 或 [UNAVAILABLE] 都表示"本轮没有可用结论"
     * @param cooled 本轮已标记失败的域名
     * @param pinned 用户手动钉住的图源，null/空白 = 自动
     * @param chapterNamed 章节接口点名的域名（按优先级，通常 currentImgDomain 在前）
     */
    fun select(
        candidates: List<String>,
        delays: Map<String, Long>,
        cooled: Set<String>,
        pinned: String?,
        chapterNamed: List<String>,
    ): Choice {
        // 1) 手动 pin 最高。pin 指向已被移出列表的域（例如远程配置删掉了它）则视为无效，继续降级。
        pinned?.trim()?.takeIf { it.isNotEmpty() }?.let { p ->
            candidates.firstOrNull { it.equals(p, ignoreCase = true) }
                ?.let { return Choice(it, Reason.PINNED) }
        }

        // 2) 有测速数据的候选；全冷却时降级忽略冷却。
        val delayOf = { host: String -> delays.entries.firstOrNull { it.key.equals(host, ignoreCase = true) }?.value }
        val cooledSet = cooled.map { it.lowercase() }.toSet()
        val eligible = candidates.filter { (delayOf(it) ?: UNAVAILABLE) < UNAVAILABLE }
        val uncooled = eligible.filter { it.lowercase() !in cooledSet }
        val (pool, reason) = when {
            uncooled.isNotEmpty() -> uncooled to Reason.FASTEST
            eligible.isNotEmpty() -> eligible to Reason.FASTEST_COOLDOWN_IGNORED
            else -> emptyList<String>() to Reason.NONE
        }
        // minByOrNull 取第一个最小值，配合 candidates 顺序保证同延迟结果稳定。
        pool.minByOrNull { delayOf(it) ?: UNAVAILABLE }?.let { return Choice(it, reason) }

        // 3) 本轮没有任何可用测速结论：退回章节接口点名的域（它可能不在图源列表里，但确实是服务端给的域名）。
        chapterNamed.firstOrNull { it.isNotBlank() }?.let { return Choice(it.trim(), Reason.CHAPTER_NAMED) }

        // 4) 列表首个。
        candidates.firstOrNull { it.isNotBlank() }?.let { return Choice(it, Reason.FIRST_IN_LIST) }

        return Choice(null, Reason.NONE)
    }
}

/**
 * 图源失败标记表：**本轮测速内永久**，只有 [clear] 才恢复。
 *
 * 没有 TTL——原先的 30 分钟自然到期被「每轮测速 / 测活开始时整表清空」替代，
 * 因为跨轮的数据本来就该由新一轮探测覆盖，留着只会污染新一轮选域。
 * 写方是 OkHttp 线程、读方是主线程与协程，因此用并发集合。
 */
class ImgCooldown {

    private val marked = ConcurrentHashMap.newKeySet<String>()

    fun mark(host: String) {
        val key = host.trim().lowercase()
        if (key.isNotEmpty()) marked.add(key)
    }

    fun isCooling(host: String): Boolean = host.trim().lowercase() in marked

    /** 当前被标记的域名集合（小写快照）。 */
    fun snapshot(): Set<String> = marked.toSet()

    /** 每轮测速/测活开始前调用：整表清空。 */
    fun clear() {
        marked.clear()
    }
}
