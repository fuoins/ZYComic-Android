package com.zycomic.app.net

/**
 * Route lists controlled by a validated remote configuration.
 *
 * This intentionally does not accept or merge local defaults, cached routes, or runtime-learned hosts.
 */
data class AuthoritativeRouteLists(
    val lines: List<String>,
    val sources: List<String>,
)

object RouteListPolicy {

    /**
     * Replace the active lists with the supplied configuration lists, preserving their order.
     * Duplicate line URLs are collapsed by host and duplicate source domains case-insensitively.
     */
    fun replace(lines: List<String>, sources: List<String>): AuthoritativeRouteLists {
        val normalizedLines = LinkedHashMap<String, String>()
        lines.asSequence()
            .map(::normalizeLine)
            .filter(String::isNotBlank)
            .forEach { normalizedLines.putIfAbsent(lineKey(it).lowercase(), it) }

        val normalizedSources = LinkedHashMap<String, String>()
        sources.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { normalizedSources.putIfAbsent(it.lowercase(), it) }

        require(normalizedLines.isNotEmpty()) { "线路列表不能为空" }
        require(normalizedSources.isNotEmpty()) { "图源列表不能为空" }

        return AuthoritativeRouteLists(normalizedLines.values.toList(), normalizedSources.values.toList())
    }

    private fun normalizeLine(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) -> "http://${trimmed.substringAfter("://")}"
            trimmed.startsWith("https://", ignoreCase = true) -> "https://${trimmed.substringAfter("://")}"
            trimmed.isNotBlank() -> "https://$trimmed"
            else -> ""
        }
    }

    private fun lineKey(url: String): String =
        runCatching { java.net.URI(url).host?.lowercase().orEmpty() }
            .getOrDefault(url.substringAfter("://", url).substringBefore('/').lowercase())
}
