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
    private val splash by lazy {
        File(root, "app/src/main/java/com/zycomic/app/ui/StartupSplash.kt").readText()
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
    fun `remote config gate reuses the same splash shell`() {
        val gate = mainActivity.substringAfter("private fun RemoteConfigGate(")
            .substringBefore("// ==================== 桌面快捷方式")

        assertTrue(gate.contains("StartupConfigGate(loading = true"))
        assertTrue(gate.contains("StartupConfigGate(loading = false"))
        assertFalse(gate.contains("MangaGridSkeleton"))
        assertTrue(splash.contains("private fun SplashScaffold("))
        assertTrue(splash.contains("SplashBackground("))
    }

    @Test
    fun `startup page delegates to the redesigned splash without per check ticks`() {
        assertTrue(measurementPage.contains("StartupSplash("))
        assertTrue(measurementPage.contains("networkInfo = remoteInfo.substringBefore(\"　上次错误：\")"))
        assertTrue(measurementPage.contains("checks = startupChecks"))
        assertTrue(measurementPage.contains("onTogglePlatform = { platform, enable ->"))
        assertTrue(measurementPage.contains("PlatformSpeedTestCoordinator.retestAllEnabled()"))
        assertTrue(measurementPage.contains("KMR.strings.startup_platform_keep_one_toast"))
        assertTrue(measurementPage.contains("configRetest?.takeIf { it.duringStartup }?.version"))
        // 逐项打勾的检查表已经删除：进入条件是“每个平台线路+图源都可用”。
        assertFalse(mainActivity.contains("StartupPlatformSwitches("))
        assertFalse(mainActivity.contains(".result?.isReady"))
        assertFalse(mainActivity.contains("startup_checks_ready_count"))
        assertFalse(mainActivity.contains("PlatformSpeedTestCoordinator.running.collectAsState()"))
    }

    @Test
    fun `splash uses liquid glass and shows platform toggles without progress rings`() {
        assertTrue(splash.contains("StartupSplash("))
        assertTrue(splash.contains("SplashBackground("))
        assertTrue(splash.contains("GlassBox("))
        assertTrue(splash.contains("BookMark("))
        assertTrue(splash.contains("TrackSwitch("))
        assertTrue(splash.contains("PlatformChip("))
        assertTrue(splash.contains("phasesOf(platform)"))
        assertTrue(splash.contains(".result?.isReady"))
        assertTrue(splash.contains("rememberInfiniteTransition("))
        // 等待时长不可预测，因此不再有按阶段填充的进度环
        assertFalse(splash.contains("HeroRing("))
        assertFalse(splash.contains("drawArc("))
        assertTrue(splash.contains("startup_retest_all"))
        assertTrue(splash.contains("startup_guidance_copy"))
        assertFalse(splash.contains("startupChecksPassed"))
        assertFalse(splash.contains("startup_checks_ready_count"))
        // 玻璃层不能再有整块径向亮区（会在胶囊内部形成一圈长方形亮框）
        assertTrue(splash.contains("tint: Color? = null"))
        assertFalse(splash.contains("center = Offset(size.width / 2f, -0.14f * size.height)"))
        // 提示直接展开，开关不再附“已关闭”文字
        assertTrue(splash.contains("copy.split"))
        assertTrue(splash.contains("RetestButton("))
        assertFalse(splash.contains("已关闭"))
        assertFalse(splash.contains("使用提示"))
        assertFalse(splash.contains("expanded"))
    }

    @Test
    fun `launcher icon and system splash use the book mark instead of the letter z`() {
        val drawable = File(root, "app/src/main/res/drawable")
        val foreground = File(drawable, "ic_launcher_foreground.xml").readText()
        assertTrue(foreground.contains("left_page"))
        assertTrue(foreground.contains("right_page"))
        assertFalse(foreground.contains("letter_z"))
        val monochrome = File(drawable, "ic_launcher_monochrome.xml").readText()
        assertTrue(monochrome.contains("left_page"))
        assertFalse(monochrome.contains("letter_z"))
        val background = File(drawable, "ic_launcher_background.xml").readText()
        assertTrue(background.contains("#FF0FAE79"))
        assertFalse(background.contains("#FF42A5F5"))
        val systemSplash = File(drawable, "ic_splash.xml").readText()
        assertTrue(systemSplash.contains("@drawable/ic_splash_icon"))
        assertFalse(systemSplash.contains("ic_komikku_dark"))
        val splashIcon = File(drawable, "ic_splash_icon.xml").readText()
        assertTrue(splashIcon.contains("left_page") || splashIcon.contains("211.7,172.8"))
        // 深色模式曾经走 drawable-night 的旧启动图（Komikku 的 Z），必须一起换掉
        val nightSplash = File(root, "app/src/main/res/drawable-night/ic_splash.xml").readText()
        assertTrue(nightSplash.contains("@drawable/ic_splash_icon"))
        assertFalse(nightSplash.contains("ic_komikku_dark"))
        val brandMark = File(drawable, "ic_zycomic.xml").readText()
        assertFalse(brandMark.contains("letter_z"))
        assertTrue(brandMark.contains("172.8"))
    }

    @Test
    fun `startup still exposes retest and keeps the guidance copy`() {
        assertTrue(splash.contains("KMR.strings.startup_retest_all"))
        // 提示全文直接显示：不再有“展开/收起”，按钮单独居中
        assertTrue(splash.contains("contentAlignment = Alignment.Center"))
        assertTrue(splash.contains("copy.split"))
        assertEquals("重新完整测速", strings["startup_retest_all"])
        assertEquals(
            "三个平台源本身晚上就卡，建议早6点到晚5点使用。网络配置是作者自己建的定时 DoH 解析，每天两次，和神秘的网络层优化；再卡顿只能 VPN 了。\\n" +
                "首次会完整测速，后续打开会用“快测”节省时间，建议每隔一段时间重新完整测速一次。\\n" +
                "点击“筛选”按钮可调整分类和每行漫画格数；切换标签页即可切换平台，标签页支持新增、删除和修改。",
            strings["startup_guidance_copy"],
        )
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
    fun `non startup browse loading skeleton remains`() {
        val browse = File(root, "app/src/main/java/com/zycomic/app/ui/browse/BrowseScreen.kt").readText()
        assertTrue(browse.contains("loading && mangas.isEmpty() -> MangaGridSkeleton("))
    }
}
