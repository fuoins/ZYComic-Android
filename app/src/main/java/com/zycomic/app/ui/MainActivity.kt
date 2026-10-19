package com.zycomic.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import cafe.adriel.voyager.navigator.Navigator
import com.zycomic.app.data.repository.UserRepository
import com.zycomic.app.net.PlatformSpeedTestCoordinator
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.ContentPlatformAccentPrefs
import com.zycomic.app.platform.StartupPlatformPolicy
import com.zycomic.app.platform.rememberContentPlatformAccent
import com.zycomic.app.ui.browse.BrowseScreen
import com.zycomic.app.ui.browse.BrowseViewModel
import com.zycomic.app.ui.components.NetworkMaintenanceNotice
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
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.settings.screen.SettingsReaderScreen
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.base.delegate.ThemingDelegate
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import android.graphics.Color as AndroidColor

class MainActivity : BaseActivity() {

    // 最近一次由桌面快捷方式请求打开的底部 tab；-1 表示普通启动/已消费，不改变默认页。
    private var shortcutTab by mutableIntStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 根据 UiPreferences 设置 Activity XML 主题（由 BaseActivity.onCreate 统一处理）

        // 桌面快捷方式冷启动直达对应底部 tab
        shortcutTab = intent?.shortcutTab() ?: -1

        StartupPlatformPolicy.load(this)
        ContentPlatformAccentPrefs.init(this)
        com.zycomic.app.net.NetworkModule.init(this)
        setContent {
            val uiPreferences = remember { Injekt.get<UiPreferences>() }
            val appTheme by uiPreferences.appTheme().collectAsState()
            val amoled by uiPreferences.themeDarkAmoled().collectAsState()
            TachiyomiTheme(appTheme = appTheme, amoled = amoled) {
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
                        )
                    } else {
                        RemoteConfigGate(routeConfigState) {
                            bootstrapScope.launch {
                                val result = com.zycomic.app.net.RemoteConfigFetcher.checkAndApply(manual = true)
                                if (result.configurationUpdated) {
                                    com.zycomic.app.net.RemoteConfigFetcher.beginConfigRetest(result.version, duringStartup = true)
                                    try {
                                        com.zycomic.app.net.PlatformSpeedTestCoordinator.run(remoteConfigUpdated = result.configurationUpdated)
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

        // 登录态已在 App.kt 启动时处理（本地恢复+后台刷新）
    }

    // 桌面快捷方式热启动/重复点击：singleTop 下回调，切到对应底部 tab
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.shortcutTab()?.let { shortcutTab = it }
    }
}

@Composable
private fun RemoteConfigGate(
    state: com.zycomic.app.net.RouteConfigState,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 36.dp)) {
            Text("ZYComic", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(20.dp))
            StartupPlatformSwitches()
            Spacer(modifier = Modifier.height(20.dp))
            when (state) {
                com.zycomic.app.net.RouteConfigState.Loading -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("正在从网络加载线路与图源配置…", style = MaterialTheme.typography.bodyMedium)
                }
                is com.zycomic.app.net.RouteConfigState.Failed -> {
                    Text("尚未取得有效的网络配置", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onRetry) { Text("重试") }
                }
                com.zycomic.app.net.RouteConfigState.Ready -> Unit
            }
        }
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

/** 设置子页面 Dialog 标识 */
private enum class SettingsDialog {
    Appearance,
    Reader,
    TagBlock,
    BookBlock,
    DeveloperSettings,
    SpeedTest,
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

    var loginPlatform by remember { mutableStateOf<ContentPlatform?>(null) }
    val openLogin: (ContentPlatform?) -> Unit = { requestedPlatform ->
        loginPlatform = requestedPlatform
        loginOpen = true
    }

    // 共享的设置 ViewModel（启动测速 + 设置页共用）
    val settingsVm = remember { SettingsViewModel() }
    val speedTestProgress by settingsVm.speedTestProgress.collectAsState()
    val configRetest by com.zycomic.app.net.RemoteConfigFetcher.configRetest.collectAsState()
    val remoteInfo by settingsVm.remoteInfo.collectAsState()
    val platformTestText by PlatformSpeedTestCoordinator.progressText.collectAsState()
    val jmTestProgress by PlatformSpeedTestCoordinator.jmProgress.collectAsState()
    var speedTesting by remember { mutableStateOf(isLaunch && !com.zycomic.app.ui.settings.SettingsViewModel.autoTestDone) }
    val blockingSpeedTest = speedTesting || configRetest?.duringStartup == true

    // 首启向导是否已完成（持久化）；未完成则测速结束后先进入 3 页向导
    var onboardingDone by remember {
        mutableStateOf(com.zycomic.app.ui.onboarding.OnboardingPrefs.isDone(context))
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
    val openJmSpeedTestLog = { settingsDialog = SettingsDialog.JmSpeedTestLog }
    val openDataStorage = { settingsDialog = SettingsDialog.DataStorage }
    val openAbout = { settingsDialog = SettingsDialog.About }
    val blockGayTags = { requireLogin { showGayConfirm = true } }

    // 测速完成前不渲染底层页面，避免页面用默认线路发起请求；
    // 测速结束后若首启向导未完成，则先全屏进入向导，完成后才进主界面
    if (!blockingSpeedTest && onboardingDone) {
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
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.navigationBars,
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = bottomTab == 0,
                        onClick = { bottomTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = "分类") },
                        label = { Text("分类") },
                    )
                    NavigationBarItem(
                        selected = bottomTab == 1,
                        onClick = { bottomTab = 1 },
                        icon = { Icon(Icons.Default.Book, contentDescription = "书架") },
                        label = { Text("书架") },
                    )
                    NavigationBarItem(
                        selected = bottomTab == 2,
                        onClick = { bottomTab = 2 },
                        icon = { Icon(Icons.Default.History, contentDescription = "历史") },
                        label = { Text("历史") },
                    )
                    NavigationBarItem(
                        selected = bottomTab == 3,
                        onClick = { bottomTab = 3 },
                        icon = { Icon(Icons.Default.Person, contentDescription = "我的") },
                        label = { Text("我的") },
                    )
                    NavigationBarItem(
                        selected = bottomTab == 4,
                        onClick = { bottomTab = 4 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                        label = { Text("设置") },
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
                            onRequireLogin = { openLogin(null) },
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
                        onReplayOnboarding = {
                            com.zycomic.app.ui.onboarding.OnboardingPrefs.setDone(context, false)
                            onboardingDone = false
                        },
                    )
                }
            }
        }
    } else if (!blockingSpeedTest) {
        // 测速已结束但首启向导未完成：全屏进入 3 页向导
        com.zycomic.app.ui.onboarding.OnboardingHost(
            settingsVm = settingsVm,
            onFinish = { onboardingDone = true },
        )
    } else {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp)) {
                Text(
                    "网络配置：${remoteInfo.substringBefore("　上次错误：")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                NetworkMaintenanceNotice(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(20.dp))
                Text("ZYComic", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(24.dp))
                StartupPlatformSwitches()
                Spacer(modifier = Modifier.height(24.dp))
                if (configRetest?.duringStartup == true) {
                    Text(
                        "检测到网络配置更新（v${configRetest?.version}），正在使用新配置重新测速…",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                val jmProgress = jmTestProgress
                if (jmProgress != null && jmProgress.total > 0) {
                    LinearProgressIndicator(
                        progress = { jmProgress.completed.toFloat() / jmProgress.total },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                } else if (jmProgress == null && speedTestProgress.total > 0 &&
                    speedTestProgress.phase in setOf(
                        com.zycomic.app.net.SpeedTester.Phase.TESTING_LINES,
                        com.zycomic.app.net.SpeedTester.Phase.TESTING_IMAGES,
                    )
                ) {
                    LinearProgressIndicator(
                        progress = { speedTestProgress.completed.toFloat() / speedTestProgress.total },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    platformTestText ?: settingsVm.speedTestProgressMessage(speedTestProgress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
                searchProvider = if (bookId.startsWith("jm:")) SearchProvider.JM else SearchProvider.MANWA
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

@Composable
private fun StartupPlatformSwitches() {
    val policy by StartupPlatformPolicy.state.collectAsState()
    val context = LocalContext.current
    val keepOneSourceMessage = stringResource(KMR.strings.startup_platform_keep_one_toast)
    Column(modifier = Modifier.fillMaxWidth()) {
        StartupSwitch(stringResource(KMR.strings.startup_enable_manwa), !policy.disableManwa, ContentPlatform.MANWA) {
            if (!StartupPlatformPolicy.setManwaEnabled(it)) {
                Toast.makeText(context, keepOneSourceMessage, Toast.LENGTH_SHORT).show()
            }
        }
        StartupSwitch(stringResource(KMR.strings.startup_enable_jm), !policy.disableJm, ContentPlatform.JM) {
            if (!StartupPlatformPolicy.setJmEnabled(it)) {
                Toast.makeText(context, keepOneSourceMessage, Toast.LENGTH_SHORT).show()
            }
        }
        StartupSwitch(stringResource(KMR.strings.startup_use_last_speed_test), policy.useLastResult) {
            StartupPlatformPolicy.setUseLastResult(it)
        }
    }
}

@Composable
private fun StartupSwitch(
    title: String,
    checked: Boolean,
    platform: ContentPlatform? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val accent = if (platform == null) null else rememberContentPlatformAccent(platform)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(14.dp),
        color = accent?.copy(alpha = 0.10f) ?: Color.Transparent,
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = accent ?: MaterialTheme.colorScheme.onSurface)
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = accent?.let {
                    SwitchDefaults.colors(
                        checkedThumbColor = it,
                        checkedTrackColor = it.copy(alpha = 0.42f),
                        checkedBorderColor = it,
                    )
                } ?: SwitchDefaults.colors(),
            )
        }
    }
}
