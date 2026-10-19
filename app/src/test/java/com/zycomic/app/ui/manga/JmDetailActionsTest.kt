package com.zycomic.app.ui.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class JmDetailActionsTest {
    private fun projectFile(path: String): String {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app/src/main/java/com/zycomic/app/ui/manga")) }
        return Files.readString(root.resolve(path))
    }

    @Test
    fun `large counts retain every decimal digit`() {
        assertEquals("2147483647", jmCountForDisplay(Int.MAX_VALUE))
    }

    @Test
    fun `unknown counts keep the placeholder`() {
        assertEquals("—", jmCountForDisplay(null))
    }

    @Test
    fun `short comment warning resource has the requested value`() {
        val resource = projectFile("i18n-kmk/src/commonMain/moko-resources/base/strings.xml")
        val value = Regex("<string name=\"jm_comment_short_warning\">([^<]*)</string>")
            .find(resource)
            ?.groupValues
            ?.get(1)

        assertEquals("评论字数太少可能被检测导致发送失败", value)
    }

    @Test
    fun `short comment warning is bound in the detail comment actions`() {
        val actions = projectFile("app/src/main/java/com/zycomic/app/ui/manga/JmDetailActions.kt")

        assertTrue(actions.contains("stringResource(KMR.strings.jm_comment_short_warning)"))
    }

    @Test
    fun `short comment warning appears after the send button`() {
        val actions = projectFile("app/src/main/java/com/zycomic/app/ui/manga/JmDetailActions.kt")
        val confirmButton = actions.substringAfter("private fun JmCommentsDialog(")
            .substringAfter("confirmButton = {").substringBefore("dismissButton =")
        val sendButtonPosition = confirmButton.indexOf("Text(stringResource(KMR.strings.jm_comment_send))")
        val warningPosition = confirmButton.indexOf("stringResource(KMR.strings.jm_comment_short_warning)")

        assertTrue(confirmButton.contains("Column(horizontalAlignment = Alignment.End)"))
        assertTrue(confirmButton.contains("vm.postJmComment(draft) { draft = \"\" }"))
        assertTrue(sendButtonPosition >= 0, "The comment send button should remain in the confirm action")
        assertTrue(warningPosition > sendButtonPosition, "The warning should follow the comment send button")
    }
}
