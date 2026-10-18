package com.zycomic.app.ui.settings

import com.zycomic.app.net.DomainIp
import com.zycomic.app.net.JmRouteManager
import com.zycomic.app.net.JmSpeedTester
import com.zycomic.app.net.SpeedTester
import java.net.URI
import java.util.Locale

/**
 * Build the plain-text list of domains that succeeded in the latest completed speed-test snapshot.
 * Candidate order is preserved, endpoint IPs are never emitted, and the two sections are separated
 * by one blank line.
 */
internal fun buildAvailableDomainText(
    lineCandidates: List<String>,
    imageCandidates: List<String>,
    lineResults: Map<DomainIp, Long>,
    imageResults: Map<DomainIp, Long>,
    imageVerdicts: Map<DomainIp, SpeedTester.ImgVerdict>,
): String {
    val successfulLineDomains = lineResults.asSequence()
        .filter { (_, delay) -> delay < Long.MAX_VALUE }
        .map { (endpoint, _) -> endpoint.domain.lowercase(Locale.ROOT) }
        .toSet()
    val successfulImageDomains = imageResults.asSequence()
        .filter { (endpoint, delay) ->
            delay < Long.MAX_VALUE && imageVerdicts[endpoint] == SpeedTester.ImgVerdict.OK
        }
        .map { (endpoint, _) -> endpoint.domain.lowercase(Locale.ROOT) }
        .toSet()

    val lines = lineCandidates.mapNotNull { candidate ->
        runCatching { URI(candidate.trim()).host }.getOrNull()
    }.orderedSuccessfulDomains(successfulLineDomains)
    val images = imageCandidates.mapNotNull { candidate ->
        candidate.trim().takeIf(String::isNotBlank)
    }.orderedSuccessfulDomains(successfulImageDomains)

    return listOf(lines, images)
        .filter(List<String>::isNotEmpty)
        .joinToString("\n\n") { it.joinToString("\n") }
}

internal fun buildJmAvailableDomainText(
    snapshot: JmRouteManager.SpeedTestSnapshot,
    lineCandidates: List<String>,
    imageCandidates: List<String>,
): String {
    val successfulLines = snapshot.lineIpDelaysMs.asSequence()
        .filter { (_, delay) -> delay < Long.MAX_VALUE }
        .map { (endpoint, _) -> endpoint.domain.lowercase(Locale.ROOT) }
        .toSet()
    val successfulImages = snapshot.imageIpDelaysMs.asSequence()
        .filter { (endpoint, delay) ->
            delay < Long.MAX_VALUE && snapshot.imageIpVerdicts[endpoint] == JmSpeedTester.ImgVerdict.OK
        }
        .map { (endpoint, _) -> endpoint.domain.lowercase(Locale.ROOT) }
        .toSet()
    val lines = lineCandidates.mapNotNull { candidate -> runCatching { URI(candidate.trim()).host }.getOrNull() }
        .orderedSuccessfulDomains(successfulLines)
    val images = imageCandidates.mapNotNull { it.trim().takeIf(String::isNotBlank) }
        .orderedSuccessfulDomains(successfulImages)
    return listOf(lines, images).filter(List<String>::isNotEmpty).joinToString("\n\n") { it.joinToString("\n") }
}

internal enum class AvailableDomainGroup { ALL, MANWA, JM }

internal fun selectAvailableDomainText(manwaText: String, jmText: String, group: AvailableDomainGroup): String = when (group) {
    AvailableDomainGroup.ALL -> listOf(manwaText, jmText).filter(String::isNotBlank).joinToString("\n\n")
    AvailableDomainGroup.MANWA -> manwaText
    AvailableDomainGroup.JM -> jmText
}

private fun List<String>.orderedSuccessfulDomains(successfulDomains: Set<String>): List<String> {
    val seen = HashSet<String>()
    return mapNotNull { candidate ->
        val domain = candidate.trim()
        val key = domain.lowercase(Locale.ROOT)
        domain.takeIf { key in successfulDomains && seen.add(key) }
    }
}
