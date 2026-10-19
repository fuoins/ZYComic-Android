package com.zycomic.app.ui.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Offline source contracts complement the response/state policy tests without Android or live writes. */
class JmLikeActionContractTest {
    private fun source(path: String): String {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app/src/main/java/com/zycomic/app/ui/manga")) }
        return Files.readString(root.resolve("app/src/main/java/com/zycomic/app/$path"))
    }

    private fun likeAction(): String = source("ui/manga/MangaDetailViewModel.kt")
        .substringAfter("fun toggleJmLike() {").substringBefore("fun loginJm(")

    @Test
    fun `source validates decrypted like response exactly once without status allowlist`() {
        val action = source("reader/JmSource.kt").substringAfter("suspend fun toggleLike(").substringBefore("suspend fun getComments(")
        assertEquals(1, Regex("postJson\\(\"/like\"").findAll(action).count())
        assertTrue(action.contains("JmLikeResponsePolicy.requireSuccess(response)"))
        assertFalse(action.contains("\"success\""))
        assertFalse(action.contains("\"ok\""))
    }

    @Test
    fun `liked state is assigned only after successful write and error path returns without retry`() {
        val action = likeAction()
        val write = action.indexOf("JmSource.toggleLike(contentId)")
        val confirmed = action.indexOf("val confirmedLiked = JmToggleActionPolicy.likedAfterConfirmedSuccess(before)")
        val assignment = action.indexOf("copy(jmLiked = confirmedLiked)")
        assertTrue(write >= 0 && confirmed > write && assignment > confirmed)
        assertFalse(action.substring(0, write).contains("copy(jmLiked"))
        assertEquals(1, Regex("JmSource\\.toggleLike\\(").findAll(action).count())
        val failure = action.substringAfter("catch (e: Exception) {").substringBefore("// This is a confirmed write result")
        assertTrue(failure.contains("refreshJmState() && _detail.value?.jmLiked != null"))
        assertTrue(failure.contains("jmStateRefreshRequired = true"))
        assertTrue(failure.contains("return@launch"))
        assertFalse(failure.contains("copy(jmLiked"))
        assertFalse(failure.contains("likedAfterConfirmedSuccess"))
        assertTrue(action.contains("catch (e: CancellationException) {\n                    throw e"))
    }

    @Test
    fun `refresh only recovery gate returns before any write and success retains confirmed fallback`() {
        val action = likeAction()
        val gate = action.substringAfter("if (jmStateRefreshRequired) {").substringBefore("val before =")
        assertTrue(gate.contains("refreshJmState() && _detail.value?.jmLiked != null"))
        assertTrue(gate.contains("return@launch"))
        assertFalse(gate.contains("JmSource.toggleLike"))
        assertTrue(action.contains("refreshJmState(confirmedLiked)"))
        assertTrue(action.contains("jmStateRefreshRequired = !refreshed || _detail.value?.jmLiked == null"))
        val refresh = source("ui/manga/MangaDetailViewModel.kt")
            .substringAfter("private suspend fun refreshJmState(").substringBefore("fun openFolderPicker(")
        assertTrue(refresh.contains("JmToggleActionPolicy.likedAfterRefresh(fresh.jmLiked, confirmedLiked)"))
        assertTrue(refresh.contains("chapterList = _detail.value?.chapterList ?: fresh.chapterList"))
        assertFalse(refresh.substringAfter("catch (_: Exception)").contains("_detail.value ="))
    }

    @Test
    fun `network and transport remain single attempt for writes`() {
        val network = source("net/JmNetworkModule.kt").substringAfter("val client: OkHttpClient by lazy {").substringBefore("/**")
        assertTrue(network.contains(".retryOnConnectionFailure(false)"))
        assertTrue(network.contains(".followRedirects(false)"))
        val transport = source("net/JmEndpointTransportInterceptor.kt")
        assertEquals(1, Regex("chain\\.proceed\\(").findAll(transport).count())
    }
}
