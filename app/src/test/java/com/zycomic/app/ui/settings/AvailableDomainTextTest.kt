package com.zycomic.app.ui.settings

import com.zycomic.app.net.DomainIp
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
}
