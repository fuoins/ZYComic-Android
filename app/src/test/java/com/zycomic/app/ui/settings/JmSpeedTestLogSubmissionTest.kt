package com.zycomic.app.ui.settings

import com.zycomic.app.net.DomainIp
import com.zycomic.app.net.JmRouteManager
import com.zycomic.app.net.JmSpeedTestRunIdentity
import com.zycomic.app.net.JmSpeedTestSubmission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmSpeedTestLogSubmissionTest {
    private val endpoint = DomainIp("api.example", "192.0.2.1")
    private val identity = JmSpeedTestRunIdentity("run-1", 3, 7, 5, "config", "images", "policy", "private-network-identity")
    private val snapshot = JmRouteManager.SpeedTestSnapshot(
        runId = identity.runId,
        lineIpDelaysMs = mapOf(endpoint to 20),
        testedLineEndpoints = setOf(endpoint),
        lineQuickTest = true,
    )

    @Test
    fun `successful submission remains associated after selected endpoint enrichment`() {
        val submission = JmSpeedTestSubmission(identity, JmSpeedTestSubmission.State.COMPLETE, true, true)
        val json = export(snapshot.copy(roundSelectedLine = endpoint), submission)
        assertEquals("true", json.getValue("snapshotSubmissionComplete").jsonPrimitive.content)
        val last = json.getValue("lastSubmission").jsonObject
        assertEquals("true", last.getValue("matchesSnapshot").jsonPrimitive.content)
        assertEquals("true", last.getValue("complete").jsonPrimitive.content)
        assertEquals("true", json.getValue("lineQuickTest").jsonPrimitive.content)
    }

    @Test
    fun `rejected submission cannot claim the snapshot is complete`() {
        val submission = JmSpeedTestSubmission(identity, JmSpeedTestSubmission.State.HISTORY_REJECTED, true, false)
        val json = export(snapshot, submission)
        assertEquals("false", json.getValue("snapshotSubmissionComplete").jsonPrimitive.content)
        assertEquals("false", json.getValue("lastSubmission").jsonObject.getValue("complete").jsonPrimitive.content)
        assertEquals("false", json.getValue("lineEndpoints").jsonArray.single().jsonObject.getValue("testedThisRound").jsonPrimitive.content)
    }

    @Test
    fun `latest failed attempt is explicitly separate from the retained historical snapshot`() {
        val submission = JmSpeedTestSubmission(identity.copy(runId = "run-2"), JmSpeedTestSubmission.State.CANCELLED)
        val json = export(snapshot, submission)
        assertEquals(JsonNull, json.getValue("snapshotSubmissionComplete"))
        assertEquals("false", json.getValue("lastSubmission").jsonObject.getValue("matchesSnapshot").jsonPrimitive.content)
        assertEquals("run-1", json.getValue("runId").jsonPrimitive.content)
        assertEquals("run-2", json.getValue("lastSubmission").jsonObject.getValue("runId").jsonPrimitive.content)
        assertEquals("false", json.getValue("lineEndpoints").jsonArray.single().jsonObject.getValue("testedThisRound").jsonPrimitive.content)
    }

    @Test
    fun `submission export contains useful status but no raw network identity`() {
        val json = export(snapshot, JmSpeedTestSubmission(identity, JmSpeedTestSubmission.State.SNAPSHOT_REJECTED))
        val raw = json.toString()
        assertTrue(raw.contains("SNAPSHOT_REJECTED"))
        assertTrue(raw.contains("snapshotSubmitted"))
        assertTrue(raw.contains("historiesSaved"))
        assertFalse(raw.contains("private-network-identity"))
        assertFalse(raw.contains("networkIdentity"))
    }

    private fun export(snapshot: JmRouteManager.SpeedTestSnapshot, submission: JmSpeedTestSubmission) = Json.parseToJsonElement(
        encodeJmSpeedTestLog(
            snapshot = snapshot,
            lastSubmission = submission,
            routeLogs = emptyList(),
            selectedLine = endpoint.domain,
            selectedLineEndpoint = endpoint,
            selectedImageDomain = "img.example",
            selectedImageEndpoint = null,
            autoSelect = true,
        ),
    ).jsonObject
}
