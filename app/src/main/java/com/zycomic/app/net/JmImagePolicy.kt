package com.zycomic.app.net

import java.security.MessageDigest
import java.util.Locale

/** JM 图床路由与图片重排的纯策略，不依赖 Android、网络或用户设置。 */
object JmImagePolicy {

    const val DEFAULT_SCRAMBLE_THRESHOLD = 220_980
    const val MID_SCRAMBLE_THRESHOLD = 268_850
    const val HIGH_SCRAMBLE_THRESHOLD = 421_926

    val DEFAULT_IMAGE_DOMAINS = listOf(
        "cdn-msp.jmapinodeudzn.net",
        "cdn-msp2.jmapinodeudzn.net",
        "cdn-msp3.jmapinodeudzn.net",
        "cdn-msp4.jmapinodeudzn.net",
    )

    /** 去除空项并按首次出现顺序大小写不敏感地去重。 */
    fun deduplicateImageHosts(values: List<String>): List<String> {
        val seen = HashSet<String>()
        return values.map(String::trim)
            .filter {
                it.isNotEmpty() && !it.split('.').any { label ->
                    label.equals("msp1", ignoreCase = true) || label.equals("cdn-msp1", ignoreCase = true)
                } && seen.add(it.lowercase(Locale.ROOT))
            }
    }

    /** 使用 sources2 的原始优先顺序；整池为空或全部不可用时按官方静态兜底顺序尝试。 */
    fun imageCandidates(
        configured: List<String>,
        availableHosts: Set<String>,
        fallback: List<String> = DEFAULT_IMAGE_DOMAINS,
    ): List<String> {
        val available = availableHosts.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        val staticPool = deduplicateImageHosts(configured)
        val healthyStatic = staticPool.filter { it.lowercase(Locale.ROOT) in available }
        if (healthyStatic.isNotEmpty()) return healthyStatic

        val fallbackPool = deduplicateImageHosts(fallback)
        val healthyFallback = fallbackPool.filter { it.lowercase(Locale.ROOT) in available }
        return healthyFallback.ifEmpty { fallbackPool }
    }

    fun chapterId(path: String): Long? {
        val parts = path.split('/').filter(String::isNotBlank)
        if (parts.size < 4 || parts[0] != "media" || parts[1] != "photos") return null
        return parts[2].toLongOrNull()
    }

    /**
     * 按章节 ID（不是 album ID）及文件名去扩展名计算 JM 条带数。
     * `_jm_scramble` 传入的是阈值，不是条带数；空/非法值由调用方使用默认阈值。
     */
    fun scrambleCount(path: String, threshold: Int = DEFAULT_SCRAMBLE_THRESHOLD): Int {
        val parts = path.split('/').filter(String::isNotBlank)
        val chapterId = chapterId(path) ?: return 0
        val fileName = parts.last().substringBeforeLast('.', parts.last())
        if (chapterId < threshold) return 0
        if (chapterId < MID_SCRAMBLE_THRESHOLD) return 10

        // The >= side is the current default; the exact 421926 boundary remains TODO until a live sample exists.
        val base = if (chapterId >= HIGH_SCRAMBLE_THRESHOLD) 8 else 10
        val digest = MessageDigest.getInstance("MD5").digest("$chapterId$fileName".toByteArray(Charsets.UTF_8))
        val lastHexCharacter = HEX_DIGITS[digest.last().toInt() and 0x0f]
        return (lastHexCharacter.code % base) * 2 + 2
    }

    fun isScrambleBoundaryChapter(chapterId: Long): Boolean =
        chapterId in (HIGH_SCRAMBLE_THRESHOLD - 1L)..(HIGH_SCRAMBLE_THRESHOLD + 1L)

    private const val HEX_DIGITS = "0123456789abcdef"
}
