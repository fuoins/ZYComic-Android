package com.zycomic.app.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Locale

/** Builds a compact diagnostic document from the completed snapshot only. */
internal object SpeedTestLogJson {

    fun encode(snapshot: RouteManager.SpeedTestSnapshot, appVersion: String): String {
        require(snapshot.canExport) { "No complete diagnostic snapshot" }

        val lineEndpoints = snapshot.lineDelays.entries.sortedWith(endpointOrder()).map { (endpoint, delay) ->
            endpointJson("line", endpoint, snapshot.lineDiagnostics.getValue(endpoint), delay)
        }
        val imageEndpoints = snapshot.imgDelays.entries.sortedWith(endpointOrder()).map { (endpoint, delay) ->
            endpointJson("image", endpoint, snapshot.imageDiagnostics.getValue(endpoint), delay)
        }
        val endpoints = lineEndpoints + imageEndpoints
        val startedAt = requireNotNull(snapshot.startedAtEpochMs)
        val completedAt = requireNotNull(snapshot.completedAtEpochMs)
        val networkPath = requireNotNull(snapshot.networkPath)

        return buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put("appVersion", JsonPrimitive(appVersion))
            put("startedAt", JsonPrimitive(Instant.ofEpochMilli(startedAt).toString()))
            put("completedAt", JsonPrimitive(Instant.ofEpochMilli(completedAt).toString()))
            put("durationMs", JsonPrimitive((completedAt - startedAt).coerceAtLeast(0L)))
            put("remoteConfigVersion", snapshot.remoteVersion?.let { JsonPrimitive(it) } ?: JsonNull)
            put(
                "networkPath",
                buildJsonObject {
                    put("mode", JsonPrimitive(if (networkPath.directStrategy) "direct" else "vpn"))
                    put("directStrategy", JsonPrimitive(networkPath.directStrategy))
                    put("systemVpnActive", JsonPrimitive(networkPath.systemVpnActive))
                    put("followSystemVpnEnabled", JsonPrimitive(networkPath.followSystemVpnEnabled))
                },
            )
            put(
                "summary",
                buildJsonObject {
                    put("line", summaryJson(snapshot.lineDelays, snapshot.lineDiagnostics))
                    put("image", summaryJson(snapshot.imgDelays, snapshot.imageDiagnostics))
                    put("totalEndpoints", JsonPrimitive(endpoints.size))
                    put("successfulEndpoints", JsonPrimitive(endpoints.count { it.jsonObject["success"] == JsonPrimitive(true) }))
                    put("failedEndpoints", JsonPrimitive(endpoints.count { it.jsonObject["success"] == JsonPrimitive(false) }))
                },
            )
            put("endpoints", JsonArray(endpoints))
        }.toString()
    }

    private fun endpointOrder() = compareBy<Map.Entry<DomainIp, Long>>(
        { it.key.domain.lowercase(Locale.ROOT) },
        { it.key.ip.lowercase(Locale.ROOT) },
    )

    private fun endpointJson(
        type: String,
        endpoint: DomainIp,
        diagnostic: SpeedTestEndpointDiagnostic,
        delayMs: Long,
    ): JsonObject {
        val lastAttempt = diagnostic.attempts.lastOrNull()
        return buildJsonObject {
            put("type", JsonPrimitive(type))
            put("domain", JsonPrimitive(endpoint.domain))
            put("ip", JsonPrimitive(endpoint.ip))
            put("success", JsonPrimitive(delayMs < Long.MAX_VALUE))
            put("latencyMs", diagnostic.latencyMs?.let { JsonPrimitive(it) } ?: JsonNull)
            put("elapsedMs", JsonPrimitive(diagnostic.elapsedMs))
            put("httpStatus", lastAttempt?.httpStatus?.let { JsonPrimitive(it) } ?: JsonNull)
            put("contentType", lastAttempt?.contentType?.let { JsonPrimitive(it) } ?: JsonNull)
            put("failureCategory", diagnostic.failureCategory?.let { JsonPrimitive(it.jsonValue()) } ?: JsonNull)
            put("imageVerdict", diagnostic.imageVerdict?.let { JsonPrimitive(it) } ?: JsonNull)
            put("fallbackAttempted", JsonPrimitive(diagnostic.fallbackAttempted))
            put("attempts", JsonArray(diagnostic.attempts.map(::attemptJson)))
        }
    }

    private fun attemptJson(attempt: SpeedTestProbeAttempt): JsonObject = buildJsonObject {
        put("probe", JsonPrimitive(attempt.probe))
        put("success", JsonPrimitive(attempt.success))
        put("elapsedMs", JsonPrimitive(attempt.elapsedMs))
        put("httpStatus", attempt.httpStatus?.let { JsonPrimitive(it) } ?: JsonNull)
        put("contentType", attempt.contentType?.let { JsonPrimitive(it) } ?: JsonNull)
        put("failureCategory", attempt.failureCategory?.let { JsonPrimitive(it.jsonValue()) } ?: JsonNull)
    }

    private fun summaryJson(
        delays: Map<DomainIp, Long>,
        diagnostics: Map<DomainIp, SpeedTestEndpointDiagnostic>,
    ): JsonObject {
        val successfulEndpoints = delays.keys.filter { diagnostics.getValue(it).success }
        val allDomains = delays.keys.map { it.domain.lowercase(Locale.ROOT) }.toSet()
        val successfulDomains = successfulEndpoints.map { it.domain.lowercase(Locale.ROOT) }.toSet()
        return buildJsonObject {
            put("totalEndpoints", JsonPrimitive(delays.size))
            put("successfulEndpoints", JsonPrimitive(successfulEndpoints.size))
            put("failedEndpoints", JsonPrimitive(delays.size - successfulEndpoints.size))
            put("uniqueDomains", JsonPrimitive(allDomains.size))
            put("successfulDomains", JsonPrimitive(successfulDomains.size))
            put("failedDomains", JsonPrimitive(allDomains.size - successfulDomains.size))
        }
    }

    private fun SpeedTestFailureCategory.jsonValue(): String = name.lowercase(Locale.ROOT)
}
