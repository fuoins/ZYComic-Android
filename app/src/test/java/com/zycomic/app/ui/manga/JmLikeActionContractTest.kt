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
    fun `source validates documented success response and sends only album id`() {
        val action = source("reader/JmSource.kt").substringAfter("suspend fun toggleLike(").substringBefore("suspend fun getComments(")
        assertEquals(1, Regex("postJson\\(\"/like\"").findAll(action).count())
        assertTrue(action.contains("JmLikeResponsePolicy.requireSuccess(response)"))
        assertTrue(action.contains("mapOf(\"id\" to albumId)"))
        assertFalse(action.contains("like_type"))
    }

    @Test
    fun `like state and count change only after successful write and never toggle off`() {
        val action = likeAction()
        val write = action.indexOf("JmSource.toggleLike(contentId)")
        val confirmed = action.indexOf("JmLikeStatePolicy.confirmSuccess(checkNotNull(accountAlbumKey), beforeLikes)")
        val assignment = action.indexOf("let(::applyJmLikeState)")
        assertTrue(write >= 0 && confirmed > write && assignment > confirmed)
        assertFalse(action.substring(0, write).contains("confirmSuccess"))
        assertEquals(1, Regex("JmSource\\.toggleLike\\(").findAll(action).count())
        val failure = action.substringAfter("} catch (e: Exception) {").substringBefore("return@launch")
        assertTrue(action.contains("jmLikeOutcomeUnknownSessionRevision = sessionRevision"))
        assertTrue(action.contains("outcomeUnknownForCurrentSession"))
        assertTrue(failure.contains("refreshJmState()"))
        assertTrue(action.contains("return@launch\n                }\n                if (JmAuthStore.sessionRevision"))
        assertFalse(action.contains("已取消点赞"))
        assertTrue(action.contains("catch (e: CancellationException) {\n                    throw e"))
    }

    @Test
    fun `album refresh merges local success only for the active login session`() {
        val refresh = source("ui/manga/MangaDetailViewModel.kt")
            .substringAfter("private suspend fun refreshJmState(").substringBefore("fun openFolderPicker(")
        assertTrue(refresh.contains("applyJmLikeState(fresh)"))
        val merge = source("ui/manga/JmLikeStatePolicy.kt")
        assertTrue(merge.contains("confirmation?.accountAlbumKey == currentAccountAlbumKey"))
        assertTrue(merge.contains("JmLikeState(liked = true"))
        assertTrue(source("ui/manga/MangaDetailViewModel.kt").contains("getSharedPreferences(\"jm_liked_albums\""))
        assertTrue(refresh.contains("chapterList = _detail.value?.chapterList ?: fresh.chapterList"))
        assertFalse(refresh.substringAfter("catch (_: Exception)").contains("_detail.value ="))
    }

    @Test
    fun `album like uses documented multipart id only and session keyed detail model`() {
        val adapter = source("reader/JmSource.kt")
        val like = adapter.substringAfter("suspend fun toggleLike(").substringBefore("suspend fun getComments(")
        assertTrue(like.contains("mapOf(\"id\" to albumId)"))
        assertFalse(like.contains("like_type"))
        val post = adapter.substringAfter("private suspend fun postJson(").substringBefore("private fun parsePromote(")
        assertTrue(post.contains("if (path == \"/like\")"))
        assertTrue(post.contains("okhttp3.MultipartBody.FORM"))
        assertTrue(post.contains("addFormDataPart(key, value)"))
        val screen = source("ui/manga/MangaDetailScreen.kt")
        assertTrue(screen.contains("remember(bookId, detailSession)"))
        assertTrue(screen.contains("onDispose { vm.dispose() }"))
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
