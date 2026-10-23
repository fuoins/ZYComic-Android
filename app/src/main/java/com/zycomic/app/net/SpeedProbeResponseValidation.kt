package com.zycomic.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Validate the complete decrypted response, never a successful-looking prefix. */
internal object SpeedProbeResponseValidation {
    fun isJsonObject(body: ByteArray): Boolean =
        runCatching { Json.parseToJsonElement(body.toString(Charsets.UTF_8)) is JsonObject }.getOrDefault(false)
}
