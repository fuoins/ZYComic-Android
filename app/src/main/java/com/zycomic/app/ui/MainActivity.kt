package com.zycomic.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cafe.adriel.voyager.navigator.Navigator
import com.zycomic.app.net.PlatformSpeedTestCoordinator
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.ContentPlatformAccentPrefs
import com.zycomic.app.platform.StartupPlatformPolicy
import com.zycomic.app.reader.ReaderDetailRoute
import com.zycomic.app.ui.browse.BrowseScreen
import com.zycomic.app.ui.browse.BrowseViewModel
import com.zycomic.app.ui.components.AppBackdropState
import com.zycomic.app.ui.components.AppCoverBackdrop
import com.zycomic.app.ui.components.AppearanceColors
import com.zycomic.app.ui.components.AppearancePreviewState
import com.zycomic.app.ui.components.AppearanceQuickPanel
import com.zycomic.app.ui.components.BackdropSources
import com.zycomic.app.ui.components.GlassShell
import com.zycomic.app.ui.components.LocalAppHaze
import com.zycomic.app.ui.components.glassSurface
import com.zycomic.app.ui.history.HistoryScreen
import com.zycomic.app.ui.history.HistoryViewModel
import com.zycomic.app.ui.library.LibraryScreen
import com.zycomic.app.ui.library.LibraryViewModel
import com.zycomic.app.ui.login.LoginOverlay
import com.zycomic.app.ui.manga.MangaDetailOverlay
import com.zycomic.app.ui.profile.ProfileScreen
import com.zycomic.app.ui.search.SearchOverlay
import com.zycomic.app.ui.search.SearchProvider
import com.zycomic.app.ui.settings.AboutScreen
import com.zycomic.app.ui.settings.BookBlockScreen
import com.zycomic.app.ui.settings.DataStorageScreen
import com.zycomic.app.ui.settings.DeveloperSettingsScreen
import com.zycomic.app.ui.settings.JmSpeedTestLogScreen
import com.zycomic.app.ui.settings.SettingsMainScreen
import com.zycomic.app.ui.settings.SettingsViewModel
import com.zycomic.app.ui.settings.SpeedTestScreen
import com.zycomic.app.ui.settings.TagBlockScreen
import com.zycomic.app.ui.settings.ZYSettingsAppearanceScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.settings.screen.SettingsReaderScreen
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import android.graphics.Color as AndroidColor

class MainActivity : BaseActivity() {

    companion object {
        fun newReaderDetailIntent(context: Context, sourceId: Long, mangaUrl: String): Intent? {
            val bookId = ReaderDetailRoute.bookId(sourceId, mangaUrl) ?: return null
            return Intent(context, MainActivity::class.java).apply {
                action = ReaderDetailRoute.ACTION
                putExtra(ReaderDetailRoute.EXTRA_BOOK_ID, bookId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }

    // 最近一次由桌面快捷方式请求打开的底部 tab；-1 表示普通启动/已消费，不改变默认页。
    private var shortcutTab by mutableIntStateOf(-1)
    private var readerDetailBookId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 根据 UiPreferences 设置 Activity XML 主题（由 BaseActivity.onCreate 统一处理）

        // 桌面快捷方式冷启动直达对应底部 tab
        shortcutTab = intent?.shortcutTab() ?: -1
        readerDetailBookId = intent?.readerDetailBookId()

        StartupPlatformPolicy.load(this)
        ContentPlatformAccentPrefs.init(this)
        com.zycomic.app.net.NetworkModule.init(this)
        com.zycomic.app.ui.components.GlassPrefs.init(this)
        com.zycomic.app.ui.components.BackdropSources.init(this)
        com.zycomic.app.ui.components.AppearanceColors.init(this)
        setContent {
            val uiPreferences = remember { Injekt.get<UiPreferences>() }
            val appTheme by uiPreferences.appTheme().collectAsState()
            val amoled by uiPreferences.themeDarkAmoled().collectAsState()
            TachiyomiTheme(appTheme = appTheme, amoled = amoled) {
                // 外观页可自定义界面主色（弹窗按钮/选中文字/进度条等原本跟随主题 primary 的地方）。
                val customAccent by com.zycomic.app.ui.components.AppearanceColors.uiAccent.collectAsState()
                val baseScheme = MaterialTheme.colorScheme
                val themedScheme = customAccent?.let { accent ->
                    val onAccent = if (accent.luminance() > 0.55f) Color.Black else Color.White
                    baseScheme.copy(
                        primary = accent,
                        onPrimary = onAccent,
                        primaryContainer = accent.copy(alpha = 0.18f),
                        onPrimaryContainer = accent,
                    )
                } ?: baseScheme
                MaterialTheme(colorScheme = themedScheme) {
                    CompositionLocalProvider(
                        LocalTextStyle provides MaterialTheme.typography.bodySmall,
                        LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                    ) {
                        val routeConfigState by com.zycomic.app.net.DevConfig.routeConfigState.collectAsState()
                        val bootstrapScope = rememberCoroutineScope()
                        if (routeConfigState is com.zycomic.app.net.RouteConfigState.Ready) {
                            AppContent(
                                isLaunch = savedInstanceState == null,
                                shortcutTab = shortcutTab,
                                onShortcutTabConsumed = { shortcutTab = -1 },
                                readerDetailBookId = readerDetailBookId,
                                onReaderDetailConsumed = {
                                    readerDetailBookId = null
                                    intent?.removeExtra(ReaderDetailRoute.EXTRA_BOOK_ID)
                                },
                            )
                        } else {
                            RemoteConfigGate(routeConfigState) {
                                bootstrapScope.launch {
                                    val result = com.zycomic.app.net.RemoteConfigFetcher.checkAndApply(manual = true)
                                    if (result.configurationUpdated) {
                                        com.zycomic.app.net.RemoteConfigFetcher.beginConfigRetest(result.version, duringStartup = true)
                                        try {
                                            com.zycomic.app.net.PlatformSpeedTestCoordinator.run(
                                                remoteConfigUpdated = result.configurationUpdated,
                                                isStartupRun = true,
                                            )
                                        } finally {
                                            com.zycomic.app.net.RemoteConfigFetcher.finishConfigRetest(result.version)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 登录态已在 App.kt 启动时处理（本地恢复+后台刷新）
    }

    // 桌面快捷方式热启动/重复点击：singleTop 下回调，切到对应底部 tab
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.shortcutTab()?.let { shortcutTab = it }
        intent.readerDetailBookId()?.let { readerDetailBookId = it }
    }
}

@Composable
private fun RemoteConfigGate(
    state: com.zycomic.app.net.RouteConfigState,
    onRetry: () -> Unit,
) {
    when (state) {
        com.zycomic.app.net.RouteConfigState.Loading ->
            StartupConfigGate(loading = true, detail = null, onRetry = onRetry)
        is com.zycomic.app.net.RouteConfigState.Failed ->
            StartupConfigGate(loading = false, detail = state.message, onRetry = onRetry)
        com.zycomic.app.net.RouteConfigState.Ready -> Unit
    }
}

// ==================== 桌面快捷方式（launcher shortcuts）→ 底部 tab ====================
// 静态快捷方式用唯一 action 区分（action 一定会随启动 Intent 回传；内嵌 category/extra 不保证）。
private const val ACTION_OPEN_CATEGORY = "com.zycomic.app.action.OPEN_CATEGORY"
private const val ACTION_OPEN_SHELF = "com.zycomic.app.action.OPEN_SHELF"
private const val ACTION_OPEN_HISTORY = "com.zycomic.app.action.OPEN_HISTORY"
private const val ACTION_OPEN_MINE = "com.zycomic.app.action.OPEN_MINE"

/** 由快捷方式 intent 的 action 映射到底部 tab；普通启动(MAIN)/其它 VIEW 深度链接返回 null（走默认页）。 */
private fun Intent.shortcutTab(): Int? = when (action) {
    ACTION_OPEN_CATEGORY -> 0 // 分类
    ACTION_OPEN_SHELF -> 1 // 书架
    ACTION_OPEN_HISTORY -> 2 // 历史
    ACTION_OPEN_MINE -> 3 // 我的
    else -> null
}

private fun Intent.readerDetailBookId(): String? =
    if (action == ReaderDetailRoute.ACTION) {
        getStringExtra(ReaderDetailRoute.EXTRA_BOOK_ID)?.takeIf { it.isNotBlank() }
    } else {
        null
    }

/** 设置子页面 Dialog 标识 */
private enum class SettingsDialog {
    Appearance,
    Reader,
    TagBlock,
    BookBlock,
    DeveloperSettings,
    SpeedTest,
    PicacgSpeedTestLog,
    MaccmsSpeedTestLog,
    JmSpeedTestLog,
    DataStorage,
    About,
}

private const val MAIN_TAB_CATEGORY_STATE_KEY = "main-tab-category"
private const val MAIN_TAB_FAVORITES_STATE_KEY = "main-tab-favorites"
private const val MAIN_TAB_HISTORY_STATE_KEY = "main-tab-history"

@Composable
fun AppContent(
    isLaunch: Boolean,
    shortcutTab: Int,
    onShortcutTabConsumed: () -> Unit,
    readerDetailBookId: String? = null,
    onReaderDetailConsumed: () -> Unit = {},
) {
    val context = LocalContext.current

    // edge-to-edge 系统栏适配：根据背景亮度决定状态栏图标明暗
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val statusBarBg = MaterialTheme.colorScheme.surface
    LaunchedEffect(isSystemInDarkTheme, statusBarBg) {
        val lightStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.BLACK)
        val darkStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        (context as androidx.activity.ComponentActivity).enableEdgeToEdge(
            statusBarStyle = if (statusBarBg.luminance() > 0.5f) lightStyle else darkStyle,
            navigationBarStyle = if (isSystemInDarkTheme) darkStyle else lightStyle,
        )
    }

    var bottomTab by androidx.compose.runtime.saveable.rememberSaveable {
        // 0分类 1书架 2历史 3我的 4设置；冷启动若来自桌面快捷方式则直达对应 tab
        mutableIntStateOf(shortcutTab.takeIf { it in 0..3 } ?: 0)
    }
    val tabStateHolder = rememberSaveableStateHolder()
    // 热启动点击快捷方式：切到目标 tab 后消费，避免重组/旋转误跳
    LaunchedEffect(shortcutTab) {
        if (shortcutTab in 0..3) {
            bottomTab = shortcutTab
            onShortcutTabConsumed()
        }
    }

    // 覆盖层状态
    /**
     * 详情覆盖层的返回栈（最新一本在栈顶）。
     * 之前只存一个 detailBookId，从相关推荐连点几本后按返回会把整个详情层一次关掉，
     * 回不到上一本；改成栈后返回逐级退，最后一本退完才关层。
     */
    val detailStack = remember { mutableStateListOf<String>() }
    /** 压入一本（点相关推荐/搜索结果时调用）。 */
    val openDetail: (String) -> Unit = { id -> if (id.isNotBlank()) detailStack.add(id) }
    /** 退一本：栈里还有就不关层，退空了才关。 */
    val closeDetail: () -> Unit = { if (detailStack.isNotEmpty()) detailStack.removeAt(detailStack.lastIndex) }
    var showSearch by remember { mutableStateOf(false) }
    var loginOpen by remember { mutableStateOf(false) }

    var searchKeyword by remember { mutableStateOf("") }
    var searchProvider by remember { mutableStateOf<SearchProvider?>(null) }
    var pendingTag by remember { mutableStateOf<String?>(null) }

    // Reader routes carry the remote ID and platform prefix, never the local database row ID.
    LaunchedEffect(readerDetailBookId) {
        val bookId = readerDetailBookId ?: return@LaunchedEffect
        showSearch = false
        loginOpen = false
        if (detailStack.lastOrNull() != bookId) openDetail(bookId)
        onReaderDetailConsumed()
    }

    var loginPlatform by remember { mutableStateOf<ContentPlatform?>(null) }
    val openLogin: (ContentPlatform?) -> Unit = { requestedPlatform ->
        loginPlatform = requestedPlatform
        loginOpen = true
    }

    // 共享的设置 ViewModel（启动测速 + 设置页共用）
    val settingsVm = remember { SettingsViewModel() }
    val configRetest by com.zycomic.app.net.RemoteConfigFetcher.configRetest.collectAsState()
    val remoteInfo by settingsVm.remoteInfo.collectAsState()
    val platformTestSummary by PlatformSpeedTestCoordinator.startupSummary.collectAsState()
    val foregroundRoutesReady by PlatformSpeedTestCoordinator.foregroundReady.collectAsState()
    var speedTesting by remember { mutableStateOf(isLaunch && !com.zycomic.app.ui.settings.SettingsViewModel.autoTestDone) }
    var fullSpeedTestRequested by remember { mutableStateOf(false) }
    val speedTestScope = rememberCoroutineScope()
    // 完整重测一旦有平台端点就绪就放行主界面，剩余平台继续在后台测：
    // 之前把 fullSpeedTestRequested 直接算进阻塞条件，慢网下三平台全测完才进主页，
    // 表现成“点了重新完整测速后一直卡在开屏页不放行”。
    val blockingSpeedTest = speedTesting ||
        (configRetest?.duringStartup == true && !foregroundRoutesReady) ||
        (fullSpeedTestRequested && !foregroundRoutesReady)

    // 进入主界面时提示一次“已用可用节点进入、后台继续测速”：
    // 每轮测速只在就绪边沿提示，进度刷新不会重复弹；手动重新测速会重新提示一次。
    var readyToastShown by remember { mutableStateOf(false) }
    LaunchedEffect(foregroundRoutesReady, blockingSpeedTest) {
        if (!foregroundRoutesReady || blockingSpeedTest) {
            if (!foregroundRoutesReady) readyToastShown = false
            return@LaunchedEffect
        }
        if (readyToastShown) return@LaunchedEffect
        readyToastShown = true
        val enabled = ContentPlatform.entries.filter { StartupPlatformPolicy.isEnabled(it) }
        val scopeLabel = when {
            enabled.size >= ContentPlatform.entries.size -> "三平台"
            enabled.isEmpty() -> ""
            else -> enabled.joinToString("、") { platform ->
                when (platform) {
                    ContentPlatform.MANWA -> "漫蛙"
                    ContentPlatform.PICACG -> "哔咔"
                    ContentPlatform.JM -> "禁漫"
                }
            }
        }
        val prefix = if (scopeLabel.isEmpty()) "已选择可用节点" else "$scopeLabel 已选择可用节点"
        Toast.makeText(context, "${prefix}进入，后台持续测速并自动更新最快节点", Toast.LENGTH_SHORT).show()
    }

    // 设置子页面 Dialog 状态
    val settingsDialogSaver = androidx.compose.runtime.saveable.Saver<SettingsDialog?, String>(
        save = { it?.name },
        restore = { runCatching { SettingsDialog.valueOf(it) }.getOrNull() },
    )
    var settingsDialog by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = settingsDialogSaver) { mutableStateOf<SettingsDialog?>(null) }
    var showGayConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (isLaunch && !com.zycomic.app.ui.settings.SettingsViewModel.autoTestDone) {
            runCatching { settingsVm.autoSelectFastest() }
                .onFailure { Toast.makeText(context, "测速未完成，使用当前线路", Toast.LENGTH_SHORT).show() }
            com.zycomic.app.ui.settings.SettingsViewModel.autoTestDone = true
            speedTesting = false
        } else {
            speedTesting = false
        }
    }

    LaunchedEffect(Unit) {
        PlatformSpeedTestCoordinator.completions.collect { report ->
            Toast.makeText(context, report.summary(), Toast.LENGTH_LONG).show()
        }
    }

    // 收集 toast 消息
    LaunchedEffect(Unit) {
        settingsVm.toast.collect { msg ->
            if (msg != null) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                settingsVm.toast.value = null
            }
        }
    }

    // 统一的打开回调
    val openAppearance = { settingsDialog = SettingsDialog.Appearance }
    val openReader = { settingsDialog = SettingsDialog.Reader }
    // 标签屏蔽 / 一键屏蔽为账号级功能：未登录统一 Toast 并打开登录页
    val requireLogin: (() -> Unit) -> Unit = { action ->
        if (!com.zycomic.app.util.LoginGate.ensureLogin(context)) openLogin(null) else action()
    }
    val openTagBlock = { requireLogin { settingsDialog = SettingsDialog.TagBlock } }
    // 漫画屏蔽同为账号级功能：服务端接口挂在登录会话上，未登录点了只会失败
    val openBookBlock = { requireLogin { settingsDialog = SettingsDialog.BookBlock } }
    val openDeveloperSettings = { settingsDialog = SettingsDialog.DeveloperSettings }
    val openSpeedTest = { settingsDialog = SettingsDialog.SpeedTest }
    val openPicacgSpeedTestLog = { settingsDialog = SettingsDialog.PicacgSpeedTestLog }
    val openJmSpeedTestLog = { settingsDialog = SettingsDialog.JmSpeedTestLog }
    val openDataStorage = { settingsDialog = SettingsDialog.DataStorage }
    val openAbout = { settingsDialog = SettingsDialog.About }
    val blockGayTags = { requireLogin { showGayConfirm = true } }

    // 测速完成前不渲染底层页面，避免页面用默认线路发起请求；测速结束后直接进主界面。
    if (!blockingSpeedTest) {
        val browseVm = remember { BrowseViewModel() }
        val libraryVm = remember { LibraryViewModel(mode = 0) }
        val historyVm = remember { HistoryViewModel() }
        androidx.activity.compose.BackHandler(
            enabled = detailStack.isNotEmpty() || showSearch || loginOpen || settingsDialog != null,
        ) {
            when {
                settingsDialog != null -> settingsDialog = null
                detailStack.isNotEmpty() -> closeDetail()
                showSearch -> {
                    showSearch = false
                }
                loginOpen -> {
                    loginOpen = false
                    loginPlatform = null
                }
            }
        }
        // 全局背景：分页自定义图优先，其次全局自定义图，再走随机图源；切页/切 tab/来源/亮暗变化时刷新。
        val backdropEntry by AppBackdropState.current.collectAsState()
        val browseTabIndex by browseVm.mainTab.collectAsState()
        val backdropMode = if (isSystemInDarkTheme) {
            com.zycomic.app.ui.components.BackdropMode.DARK
        } else {
            com.zycomic.app.ui.components.BackdropMode.LIGHT
        }
        val backdropCustom by BackdropSources.customPaths.collectAsState()
        val backdropSources by BackdropSources.enabled.collectAsState()
        val backdropPerPage by BackdropSources.perPage.collectAsState()
        LaunchedEffect(
            bottomTab,
            browseTabIndex,
            backdropCustom,
            backdropSources,
            backdropPerPage,
            showSearch,
            backdropMode,
        ) {
            val pageKey = when {
                showSearch -> "search"
                bottomTab == 0 -> "browse"
                bottomTab == 1 -> "library"
                bottomTab == 2 -> "history"
                bottomTab == 3 -> "profile"
                else -> "settings"
            }
            AppBackdropState.refresh(pageKey, backdropMode)
        }
        // 真磨砂玻璃的模糊源：只标记**背景层**。Haze 1.7 的 hazeEffect 只绘制
        // “zIndex 低于最近祖先 hazeSource”的模糊区——玻璃必须与 source 平级（不能是 source 的后代），
        // 否则会被过滤掉、整层透明。
        val hazeState = rememberHazeState()
        CompositionLocalProvider(LocalAppHaze provides hazeState) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize().hazeSource(state = hazeState)) {
                    AppCoverBackdrop(entry = backdropEntry, modifier = Modifier.fillMaxSize())
                }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets.navigationBars,
                    bottomBar = {
                        GlassBottomBar {
                            GlassNavItem(
                                icon = Icons.Default.Home,
                                description = "分类",
                                selected = bottomTab == 0,
                                onClick = { bottomTab = 0 },
                            )
                            GlassNavItem(
                                icon = Icons.Default.Book,
                                description = "书架",
                                selected = bottomTab == 1,
                                onClick = { bottomTab = 1 },
                            )
                            GlassNavItem(
                                icon = Icons.Default.History,
                                description = "历史",
                                selected = bottomTab == 2,
                                onClick = { bottomTab = 2 },
                            )
                            GlassNavItem(
                                icon = Icons.Default.Person,
                                description = "我的",
                                selected = bottomTab == 3,
                                onClick = { bottomTab = 3 },
                            )
                            GlassNavItem(
                                icon = Icons.Default.Settings,
                                description = "设置",
                                selected = bottomTab == 4,
                                onClick = { bottomTab = 4 },
                            )
                        }
                    },
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        when (bottomTab) {
                            0 -> tabStateHolder.SaveableStateProvider(MAIN_TAB_CATEGORY_STATE_KEY) {
                                BrowseScreen(
                                    vm = browseVm,
                                    pendingTag = pendingTag,
                                    onPendingTagConsumed = { pendingTag = null },
                                    onOpenManga = openDetail,
                                    onOpenSearch = { kw, provider ->
                                        searchKeyword = kw
                                        searchProvider = provider
                                        showSearch = true
                                    },
                                    onRequireLogin = { openLogin(browseVm.currentPlatform.value) },
                                )
                            }
                            1 -> tabStateHolder.SaveableStateProvider(MAIN_TAB_FAVORITES_STATE_KEY) {
                                LibraryScreen(
                                    vm = libraryVm,
                                    onOpenManga = openDetail,
                                    onRequireLogin = { openLogin(libraryVm.currentPlatform.value) },
                                    onOpenSearch = { kw ->
                                        searchKeyword = kw
                                        searchProvider = null
                                        showSearch = true
                                    },
                                )
                            }
                            2 -> tabStateHolder.SaveableStateProvider(MAIN_TAB_HISTORY_STATE_KEY) {
                                HistoryScreen(
                                    vm = historyVm,
                                    onOpenManga = openDetail,
                                    onRequireLogin = { openLogin(historyVm.currentPlatform.value) },
                                    onOpenSearch = { kw ->
                                        searchKeyword = kw
                                        searchProvider = null
                                        showSearch = true
                                    },
                                )
                            }
                            3 -> ProfileScreen(
                                onRequireLogin = { openLogin(null) },
                                onNavigateToTab = { bottomTab = it },
                                onOpenManga = openDetail,
                                isDetailOpen = detailStack.isNotEmpty(),
                                onOpenTagBlock = openTagBlock,
                                onOpenBookBlock = openBookBlock,
                                onBlockGayTags = blockGayTags,
                            )
                            4 -> SettingsMainScreen(
                                onOpenAppearance = openAppearance,
                                onOpenReader = openReader,
                                onOpenDeveloperSettings = openDeveloperSettings,
                                onOpenDataStorage = openDataStorage,
                                onOpenAbout = openAbout,
                                guideInitialPlatform = browseVm.currentPlatform.value,
                            )
                        }
                    }
                }
            }
        }
    } else {
        val startupMode by PlatformSpeedTestCoordinator.testMode.collectAsState()
        val platformStatuses by PlatformSpeedTestCoordinator.platformStartupStatuses.collectAsState()
        val startupChecks by PlatformSpeedTestCoordinator.startupCheckStatuses.collectAsState()
        val keepOneSourceMessage = stringResource(KMR.strings.startup_platform_keep_one_toast)
        StartupSplash(
            networkInfo = remoteInfo.substringBefore("　上次错误："),
            configUpdateVersion = configRetest?.takeIf { it.duringStartup }?.version,
            fullRequested = fullSpeedTestRequested,
            mode = startupMode,
            statuses = platformStatuses,
            checks = startupChecks,
            summary = platformTestSummary,
            onTogglePlatform = { platform, enable ->
                val applied = when (platform) {
                    ContentPlatform.MANWA -> StartupPlatformPolicy.setManwaEnabled(enable)
                    ContentPlatform.PICACG -> StartupPlatformPolicy.setPicacgEnabled(enable)
                    ContentPlatform.JM -> StartupPlatformPolicy.setJmEnabled(enable)
                }
                if (!applied) Toast.makeText(context, keepOneSourceMessage, Toast.LENGTH_SHORT).show()
            },
            onRetestAll = {
                // Set synchronously so repeated taps cannot enqueue extra coroutines.
                fullSpeedTestRequested = true
                speedTestScope.launch {
                    try {
                        PlatformSpeedTestCoordinator.retestAllEnabled()
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        Toast.makeText(context, "测速未完成，使用当前线路", Toast.LENGTH_SHORT).show()
                    } finally {
                        fullSpeedTestRequested = false
                    }
                }
            },
        )
    }

    // ---- 详情覆盖层 ----
    if (detailStack.isNotEmpty()) {
        // 栈顶才是当前要展示的那本；相关推荐点击 = 往栈里再压一本，返回 = 弹栈
        val bookId = detailStack.last()
        MangaDetailOverlay(
            bookId = bookId,
            onClose = { closeDetail() },
            onOpenManga = openDetail,
            onSearchKeyword = { kw ->
                searchProvider = null
                searchKeyword = kw
                detailStack.clear()
                showSearch = true
            },
            onTagClick = { tag ->
                detailStack.clear()
                bottomTab = 0
                pendingTag = tag
            },
        )
    }

    // ---- 搜索覆盖层 ----
    if (showSearch) {
        SearchOverlay(
            initialKeyword = searchKeyword,
            initialProvider = searchProvider,
            onClose = {
                showSearch = false
                searchKeyword = ""
                searchProvider = null
            },
            onOpenManga = openDetail,
        )
    }

    // ---- 登录覆盖层 ----
    if (loginOpen) {
        LoginOverlay(
            onClose = {
                loginOpen = false
                loginPlatform = null
            },
            fixedPlatform = loginPlatform,
        )
    }

    // ---- 外观预览：悬浮按钮 / 快速调整浮窗 ----
    val appearancePreview by AppearancePreviewState.active.collectAsState()
    val quickPanelExpanded by AppearancePreviewState.panelExpanded.collectAsState()
    if (appearancePreview && settingsDialog == null) {
        // 透明全屏容器不拦截触摸：预览时主界面照常可点。
        Box(modifier = Modifier.fillMaxSize()) {
            if (quickPanelExpanded) {
                AppearanceQuickPanel(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 92.dp),
                    onMinimize = { AppearancePreviewState.setPanelExpanded(false) },
                )
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 96.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 打开浮窗：不离开主界面就能调参数。
                    ExtendedFloatingActionButton(
                        onClick = { AppearancePreviewState.setPanelExpanded(true) },
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                        text = { Text("调整") },
                    )
                    // 返回完整外观设置页（会恢复到刚才的标签和滚动位置）。
                    ExtendedFloatingActionButton(
                        onClick = {
                            AppearancePreviewState.exit()
                            settingsDialog = SettingsDialog.Appearance
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                        text = { Text("返回外观") },
                    )
                }
            }
        }
    }

    // ---- 设置子页面全屏 Dialog ----
    when (settingsDialog) {
        SettingsDialog.Appearance -> {
            KomikkuSettingsDialog(
                screen = ZYSettingsAppearanceScreen,
                onClose = { settingsDialog = null },
            )
        }
        SettingsDialog.Reader -> {
            KomikkuSettingsDialog(
                screen = SettingsReaderScreen,
                onClose = { settingsDialog = null },
            )
        }
        SettingsDialog.TagBlock -> {
            Dialog(
                onDismissRequest = { settingsDialog = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                TagBlockScreen(vm = settingsVm, onClose = { settingsDialog = null })
            }
        }
        SettingsDialog.BookBlock -> {
            Dialog(
                onDismissRequest = { settingsDialog = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                BookBlockScreen(vm = settingsVm, onClose = { settingsDialog = null })
            }
        }
        SettingsDialog.DeveloperSettings -> {
            Dialog(
                onDismissRequest = { settingsDialog = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                DeveloperSettingsScreen(
                    vm = settingsVm,
                    onClose = { settingsDialog = null },
                    onOpenManwaSpeedTest = openSpeedTest,
                    onOpenPicacgSpeedTest = openPicacgSpeedTestLog,
                    onOpenMaccmsSpeedTest = { settingsDialog = SettingsDialog.MaccmsSpeedTestLog },
                    onOpenJmSpeedTest = openJmSpeedTestLog,
                    onCheckUpdates = { settingsVm.checkRemoteConfig() },
                )
            }
        }
        SettingsDialog.SpeedTest -> {
            Dialog(
                onDismissRequest = { settingsDialog = SettingsDialog.DeveloperSettings },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                SpeedTestScreen(vm = settingsVm, onClose = { settingsDialog = SettingsDialog.DeveloperSettings })
            }
        }
        SettingsDialog.PicacgSpeedTestLog -> {
            Dialog(
                onDismissRequest = { settingsDialog = SettingsDialog.DeveloperSettings },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                com.zycomic.app.ui.settings.PicacgSpeedTestLogScreen(onClose = { settingsDialog = SettingsDialog.DeveloperSettings })
            }
        }
        SettingsDialog.MaccmsSpeedTestLog -> {
            Dialog(
                onDismissRequest = { settingsDialog = SettingsDialog.DeveloperSettings },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                com.zycomic.app.ui.settings.MaccmsSpeedTestLogScreen(onClose = { settingsDialog = SettingsDialog.DeveloperSettings })
            }
        }
        SettingsDialog.JmSpeedTestLog -> {
            Dialog(
                onDismissRequest = { settingsDialog = SettingsDialog.DeveloperSettings },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                JmSpeedTestLogScreen(onClose = { settingsDialog = SettingsDialog.DeveloperSettings })
            }
        }
        SettingsDialog.DataStorage -> {
            Dialog(
                onDismissRequest = { settingsDialog = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                DataStorageScreen(onClose = { settingsDialog = null })
            }
        }
        SettingsDialog.About -> {
            Dialog(
                onDismissRequest = { settingsDialog = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                AboutScreen(onClose = { settingsDialog = null })
            }
        }
        null -> {}
    }

    // ---- gay 标签一键屏蔽确认对话框 ----
    if (showGayConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showGayConfirm = false },
            title = { Text("确认屏蔽") },
            text = { Text("确认将 ${SettingsViewModel.GAY_TAGS.size} 个gay相关标签加入屏蔽列表？") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showGayConfirm = false
                    settingsVm.blockGayTags()
                }) { Text("确认") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showGayConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * 用 Voyager Navigator 包装 komikku 原生 Settings Screen，使其能在 Dialog 中运行。
 *
 * - Navigator 自动提供 LocalNavigator（子页面 push/pop 需要）。
 * - LocalBackPress 提供返回按钮行为：能 pop 就 pop，否则关闭 Dialog。
 */
@Composable
private fun KomikkuSettingsDialog(
    screen: cafe.adriel.voyager.core.screen.Screen,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Navigator(screen = screen) { navigator ->
            CompositionLocalProvider(
                LocalBackPress provides {
                    if (navigator.canPop) navigator.pop() else onClose()
                },
            ) {
                navigator.lastItem.Content()
            }
        }
    }
}

/**
 * 液态玻璃底栏：真磨砂悬浮胶囊（Haze backdrop blur）——
 * 灰白玻璃淡染 + 白描边 + 顶部内高光 + 底部内阴影，圆角胶囊悬空，不带主题色。
 */
@Composable
private fun GlassBottomBar(content: @Composable RowScope.() -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    GlassShell(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(50),
        radius = 999.dp,
        part = com.zycomic.app.ui.components.GlassPart.BOTTOM_BAR,
        stroke = true,
        elevated = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .drawBehind {
                    // 顶部内高光（HTML: inset 0 1px 0 rgba(255,255,255,.60)）
                    drawLine(
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = if (dark) 0.20f else 0.85f),
                                Color.Transparent,
                            ),
                        ),
                        start = Offset(size.width * 0.15f, 0.8f),
                        end = Offset(size.width * 0.85f, 0.8f),
                        strokeWidth = 1.4f,
                    )
                    // 底部内阴影线
                    drawLine(
                        color = Color.Black.copy(alpha = if (dark) 0.25f else 0.06f),
                        start = Offset(0f, size.height - 0.5f),
                        end = Offset(size.width, size.height - 0.5f),
                        strokeWidth = 1.dp.toPx(),
                    )
                },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

/** 底栏图标项：选中 = 白色圆形玻璃底（正圆指示器），未选中只显示图标；无文字。 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.GlassNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(
                    if (selected) {
                        // 选中指示器 = 白色玻璃圆，模糊度/透明度由外观页「底栏选中圆」独立控制。
                        Modifier.glassSurface(
                            shape = RoundedCornerShape(50),
                            radius = 999.dp,
                            part = com.zycomic.app.ui.components.GlassPart.BOTTOM_SELECTED,
                            stroke = false,
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            val partTextColors by AppearanceColors.partTextColors.collectAsState()
            val part = if (selected) {
                com.zycomic.app.ui.components.GlassPart.BOTTOM_SELECTED
            } else {
                com.zycomic.app.ui.components.GlassPart.BOTTOM_BAR
            }
            Icon(
                icon,
                contentDescription = description,
                modifier = Modifier.size(20.dp),
                tint = partTextColors[part.key]
                    ?: if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
