package com.zycomic.app.picacg

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PicacgDiagnosticPolicyTest {
    @Test
    fun `distinguishes documented website roots from API bases without rewriting them`() {
        assertEquals(
            PicacgDiagnosticPolicy.BaseKind.WEBSITE_ROOT,
            PicacgDiagnosticPolicy.classifyBase("https://picacomic.com"),
        )
        assertEquals(
            PicacgDiagnosticPolicy.BaseKind.WEBSITE_ROOT,
            PicacgDiagnosticPolicy.classifyBase("https://www.picacomic.com/"),
        )
        assertEquals(
            PicacgDiagnosticPolicy.BaseKind.WEBSITE_ROOT,
            PicacgDiagnosticPolicy.classifyBase("https://picacomic.com/auth/sign-in"),
        )
        assertEquals(
            PicacgDiagnosticPolicy.BaseKind.UNKNOWN,
            PicacgDiagnosticPolicy.classifyBase("https://custom.example/home"),
        )
        assertEquals(
            PicacgDiagnosticPolicy.BaseKind.API_BASE,
            PicacgDiagnosticPolicy.classifyBase("https://picaapi.picacomic.com"),
        )
        assertEquals(
            PicacgDiagnosticPolicy.BaseKind.API_BASE,
            PicacgDiagnosticPolicy.classifyBase("https://api.example/api/v2"),
        )
        assertEquals(
            PicacgDiagnosticPolicy.BaseKind.UNKNOWN,
            PicacgDiagnosticPolicy.classifyBase("https://custom.example"),
        )
        assertEquals(
            "Picacg sign-in configured endpoint=website-root",
            PicacgDiagnosticPolicy.signInBase("https://picacomic.com"),
        )
    }

    @Test
    fun `redirect diagnostics contain only fixed labels and never raw URL or Location data`() {
        val source = "http://login-user:login-pass@api.example/auth/sign-in?credential=query-value".toHttpUrl()
        val location = "https://elsewhere.example/auth/sign-in?credential=query-value&token=location-token"

        val diagnostic = PicacgDiagnosticPolicy.redirectResponse(source, 301, location)

        assertTrue(diagnostic.contains("HTTP 301"))
        assertTrue(diagnostic.contains("source=api-base"))
        assertTrue(diagnostic.contains("location=cross-host-rejected"))
        listOf(
            "login-user",
            "login-pass",
            "api.example",
            "elsewhere.example",
            "/auth/sign-in",
            "query-value",
            "location-token",
        ).forEach {
            assertFalse(diagnostic.contains(it))
        }
    }

    @Test
    fun `missing redirect location is reported without exposing request data`() {
        val source = "http://api.example/api/v2/auth/sign-in?x=1".toHttpUrl()

        val diagnostic = PicacgDiagnosticPolicy.redirectResponse(source, 301, null)

        assertEquals("Picacg HTTP 301 redirect; source=api-base; location=missing-or-invalid-location", diagnostic)
    }
}
