package com.zycomic.app.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Source contracts cover startup first-frame UI without requiring Compose or an Android runtime. */
class StartupUiContractTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/zycomic/app/ui/MainActivity.kt").isFile }

    private val mainActivity by lazy {
        File(root, "app/src/main/java/com/zycomic/app/ui/MainActivity.kt").readText()
    }
    private val measurementPage by lazy {
        mainActivity.substringAfter("} else if (!blockingSpeedTest) {")
            .substringBefore("// ---- 详情覆盖层 ----")
    }
    private val strings by lazy {
        val file = File(root, "i18n-kmk/src/commonMain/moko-resources/base/strings.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    @Test
    fun `remote config gate keeps loading copy and startup measurement but no manga grid skeleton`() {
        val gate = mainActivity.substringAfter("private fun RemoteConfigGate(")
            .substringBefore("// ==================== 桌面快捷方式")

        assertTrue(gate.contains("StartupPlatformSwitches()"))
        assertTrue(gate.contains("正在从网络加载线路与图源配置…"))
        assertTrue(gate.contains("StartupNetworkStatus("))
        assertTrue(gate.contains("StartupBrand()"))
        assertTrue(gate.contains(".statusBarsPadding().navigationBarsPadding()"))
        assertTrue(gate.contains(".verticalScroll(rememberScrollState())"))
        assertFalse(gate.contains("MangaGridSkeleton"))
    }

    @Test
    fun `startup keeps dynamic config and per platform progress without redundant running summary`() {
        assertTrue(measurementPage.contains("StartupNetworkStatus(remoteInfo.substringBefore(\"　上次错误：\"))"))
        assertTrue(measurementPage.contains("StartupPlatformSwitches()"))
        assertTrue(measurementPage.contains("platformTestSummary?.let { summary ->"))
        assertFalse(measurementPage.contains("正在准备启动测速…"))
        val coordinator = File(root, "app/src/main/java/com/zycomic/app/net/PlatformSpeedTestCoordinator.kt").readText()
        assertFalse(coordinator.contains("三平台测速进行中；各平台进度独立显示"))
        assertTrue(coordinator.contains("_startupSummary.value = null"))
        assertTrue(coordinator.contains("_startupSummary.value = startupSummary(result)"))
        val startupSwitches = mainActivity.substringAfter("private fun StartupPlatformSwitches()")
            .substringBefore("@Composable\nprivate fun StartupSwitch(")
        assertTrue(startupSwitches.contains("StartupSwitch("))
        assertTrue(startupSwitches.contains("LinearProgressIndicator("))
        assertTrue(startupSwitches.contains("startupChecksProgress(checkStatuses)"))
        assertTrue(startupSwitches.contains("startupChecksPassed(checkStatuses)"))
        assertTrue(startupSwitches.contains("stringResource(KMR.strings.startup_checks_ready_count, startupChecksPassed(checkStatuses), checkStatuses.size)"))
        assertEquals("就绪 %1\$d/%2\$d", strings["startup_checks_ready_count"])
        assertTrue(startupSwitches.contains("val passed = check.result.isReady"))
        assertTrue(startupSwitches.contains("KMR.strings.startup_check_reused"))
        assertEquals("历史有效沿用", strings["startup_check_reused"])
        val row = mainActivity.substringAfter("private fun StartupSwitch(")
        assertTrue(row.contains("status?.message ?: \"等待测速\""))
        assertTrue(row.contains("progress = { status.fraction }"))
        assertFalse(mainActivity.contains("NetworkMaintenanceNotice"))
    }

    @Test
    fun `startup warmup and auto selection share the startup speed test generation`() {
        val app = File(root, "app/src/main/java/eu/kanade/tachiyomi/App.kt").readText()
        val warmup = app.substringAfter("val warmup = launch {").substringBefore("var configurationUpdated = false")
        assertTrue(warmup.contains("PlatformSpeedTestCoordinator.awaitStartup()"))
        assertFalse(warmup.contains("PlatformSpeedTestCoordinator.run("))

        val settings = File(root, "app/src/main/java/com/zycomic/app/ui/settings/SettingsViewModel.kt").readText()
        val autoSelect = settings.substringAfter("suspend fun autoSelectFastest()").substringBefore("companion object")
        assertTrue(autoSelect.contains("PlatformSpeedTestCoordinator.awaitStartup()"))
        assertFalse(autoSelect.contains("PlatformSpeedTestCoordinator.run("))
    }

    @Test
    fun `network status sits below system bars and enlarged brand uses compact spacing`() {
        assertTrue(measurementPage.contains(".statusBarsPadding().navigationBarsPadding()"))
        assertTrue(measurementPage.contains(".verticalScroll(rememberScrollState())"))
        assertTrue(measurementPage.contains("padding(horizontal = 20.dp, vertical = 8.dp)"))
        assertTrue(measurementPage.indexOf("StartupNetworkStatus(") < measurementPage.indexOf("StartupBrand()"))
        assertFalse(measurementPage.contains("contentAlignment = Alignment.Center"))
        val brand = mainActivity.substringAfter("private fun StartupBrand()").substringBefore("@Composable")
        assertTrue(brand.contains("fontSize = 40.sp"))
        assertTrue(brand.contains("FontWeight.ExtraBold"))
        assertTrue(brand.contains("MaterialTheme.colorScheme.primary"))
        val network = mainActivity.substringAfter("private fun StartupNetworkStatus(").substringBefore("@Composable")
        assertTrue(network.contains("startup_network_status_title"))
        assertTrue(network.contains("surfaceContainerHigh"))
        assertTrue(network.contains("\n                info,"))
    }

    @Test
    fun `guidance card follows full retest button with exact copy and emphasized phrases`() {
        assertEquals("重新完整测速", strings["startup_retest_all"])
        assertEquals("网络配置", strings["startup_network_status_title"])
        assertEquals(
            "三个平台源本身晚上就卡，建议早6点到晚5点使用。网络配置是作者自己建的定时 DoH 解析，每天两次，和神秘的网络层优化；再卡顿只能 VPN 了。\\n首次会完整测速，后续打开会用“快测”节省时间，建议每隔一段时间重新完整测速一次。",
            strings["startup_guidance_copy"],
        )
        assertEquals("本身晚上就卡", strings["startup_guidance_evening"])
        assertEquals("早6点到晚5点", strings["startup_guidance_time"])
        assertEquals("首次", strings["startup_guidance_first"])
        assertEquals("神秘的网络层优化", strings["startup_guidance_network_optimization"])
        assertTrue(measurementPage.indexOf("KMR.strings.startup_retest_all") < measurementPage.indexOf("StartupGuidanceCard()"))
        val card = mainActivity.substringAfter("private fun StartupGuidanceCard()").substringBefore("@Composable")
        listOf("copy", "evening", "time", "first", "network_optimization").forEach {
            assertTrue(card.contains("KMR.strings.startup_guidance_$it"))
        }
        assertTrue(card.contains("listOf(evening, time, first, networkOptimization)"))
        assertTrue(card.contains("addStyle("))
        assertTrue(card.contains("phrase == time || isNetworkOptimization) FontWeight.ExtraBold"))
        assertTrue(card.contains("MaterialTheme.colorScheme.tertiary"))
        assertTrue(card.contains("MaterialTheme.colorScheme.tertiaryContainer"))
        assertTrue(card.contains("MaterialTheme.colorScheme.primaryContainer"))
        assertTrue(card.contains("color = MaterialTheme.colorScheme.surfaceContainer"))
        assertTrue(card.contains("lineHeight = 18.sp"))
    }

    @Test
    fun `non startup browse loading skeleton remains`() {
        val browse = File(root, "app/src/main/java/com/zycomic/app/ui/browse/BrowseScreen.kt").readText()
        assertTrue(browse.contains("loading && mangas.isEmpty() -> MangaGridSkeleton("))
    }
}
