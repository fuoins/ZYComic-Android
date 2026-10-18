package com.zycomic.app.ui.settings

import com.zycomic.app.net.DomainIp
import com.zycomic.app.net.JmRouteManager
import com.zycomic.app.net.JmSpeedTester
import com.zycomic.app.net.SpeedTester
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AvailableDomainTextTest {

    @Test
    fun `only successful domains are listed once in candidate order`() {
        val lineOne = DomainIp("line-one.example", "203.0.113.1")
        val lineOneAltIp = DomainIp("line-one.example", "203.0.113.2")
        val lineTwo = DomainIp("line-two.example", "203.0.113.3")
        val imageOne = DomainIp("image-one.example", "198.51.100.1")
        val imageTwo = DomainIp("image-two.example", "198.51.100.2")

        val result = buildAvailableDomainText(
            lineCandidates = listOf(
                "https://line-one.example/api",
                "https://LINE-ONE.example/other",
                "https://line-two.example",
            ),
            imageCandidates = listOf("image-one.example", "IMAGE-ONE.example", "image-two.example"),
            lineResults = mapOf(
                lineOne to 42L,
                lineOneAltIp to Long.MAX_VALUE,
                lineTwo to Long.MAX_VALUE,
            ),
            imageResults = mapOf(imageOne to 51L, imageTwo to Long.MAX_VALUE),
            imageVerdicts = mapOf(
                imageOne to SpeedTester.ImgVerdict.OK,
                imageTwo to SpeedTester.ImgVerdict.NO_IMAGE,
            ),
        )

        assertEquals("line-one.example\n\nimage-one.example", result)
    }

    @Test
    fun `no successful endpoints produce an empty document`() {
        val failedLine = DomainIp("line.example", "203.0.113.1")
        val failedImage = DomainIp("image.example", "198.51.100.1")

        val result = buildAvailableDomainText(
            lineCandidates = listOf("https://line.example"),
            imageCandidates = listOf("image.example"),
            lineResults = mapOf(failedLine to Long.MAX_VALUE),
            imageResults = mapOf(failedImage to Long.MAX_VALUE),
            imageVerdicts = mapOf(failedImage to SpeedTester.ImgVerdict.UNREACHABLE),
        )

        assertEquals("", result)
    }

    @Test
    fun `JM available domain text includes only successful line and image domains`() {
        val jmLine = DomainIp("jm-line.example", "203.0.113.8")
        val failedLine = DomainIp("failed-line.example", "203.0.113.9")
        val jmImage = DomainIp("jm-image.example", "198.51.100.8")
        val noImage = DomainIp("no-image.example", "198.51.100.9")
        val snapshot = JmRouteManager.SpeedTestSnapshot(
            lineIpDelaysMs = mapOf(jmLine to 52L, failedLine to Long.MAX_VALUE),
            imageIpDelaysMs = mapOf(jmImage to 73L, noImage to Long.MAX_VALUE),
            imageIpVerdicts = mapOf(jmImage to JmSpeedTester.ImgVerdict.OK, noImage to JmSpeedTester.ImgVerdict.NO_IMAGE),
        )

        assertEquals(
            "jm-line.example\n\n jm-image.example".replace("\n\n ", "\n\n"),
            buildJmAvailableDomainText(
                snapshot,
                lineCandidates = listOf("https://jm-line.example", "https://failed-line.example"),
                imageCandidates = listOf("jm-image.example", "no-image.example"),
            ),
        )
    }

    @Test
    fun `available domain export selection supports all and each platform`() {
        assertEquals("manwa.example\n\njm.example", selectAvailableDomainText("manwa.example", "jm.example", AvailableDomainGroup.ALL))
        assertEquals("manwa.example", selectAvailableDomainText("manwa.example", "jm.example", AvailableDomainGroup.MANWA))
        assertEquals("jm.example", selectAvailableDomainText("manwa.example", "jm.example", AvailableDomainGroup.JM))
    }
}
