package com.zycomic.app.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Source contracts cover startup first-frame UI without requiring Compose or an Android runtime. */
class StartupUiContractTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/zycomic/app/ui/MainActivity.kt").isFile }

    private val mainActivity by lazy {
        File(root, "app/src/main/java/com/zycomic/app/ui/MainActivity.kt").readText()
    }

    @Test
    fun `remote config gate keeps loading copy and startup measurement but no manga grid skeleton`() {
        val gate = mainActivity.substringAfter("private fun RemoteConfigGate(")
            .substringBefore("// ==================== 桌面快捷方式")

        assertTrue(gate.contains("StartupPlatformSwitches()"))
        assertTrue(gate.contains("正在从网络加载线路与图源配置…"))
        assertFalse(gate.contains("MangaGridSkeleton"))
    }

    @Test
    fun `startup measurement keeps live config status controls and progress without maintenance notice`() {
        val measurementPage = mainActivity.substringAfter("} else if (!blockingSpeedTest) {")
            .substringBefore("// ---- 详情覆盖层 ----")

        assertTrue(measurementPage.contains("\"网络配置：\${remoteInfo.substringBefore(\"　上次错误：\")}\""))
        assertTrue(measurementPage.contains("StartupPlatformSwitches()"))
        assertTrue(measurementPage.contains("KMR.strings.startup_retest_all"))
        assertTrue(measurementPage.contains("platformTestSummary ?: \"正在准备启动测速…\""))
        val startupSwitches = mainActivity.substringAfter("private fun StartupPlatformSwitches()")
            .substringBefore("@Composable\nprivate fun StartupSwitch(")
        assertTrue(startupSwitches.contains("StartupSwitch("))
        assertTrue(startupSwitches.contains("LinearProgressIndicator("))
        assertTrue(startupSwitches.contains("startupChecksProgress(checkStatuses)"))
        assertTrue(startupSwitches.contains("startupChecksPassed(checkStatuses)"))
        assertFalse(measurementPage.contains("NetworkMaintenanceNotice"))
        assertFalse(mainActivity.contains("NetworkMaintenanceNotice"))
    }

    @Test
    fun `non startup browse loading skeleton remains`() {
        val browse = File(root, "app/src/main/java/com/zycomic/app/ui/browse/BrowseScreen.kt").readText()
        assertTrue(browse.contains("loading && mangas.isEmpty() -> MangaGridSkeleton("))
    }
}
