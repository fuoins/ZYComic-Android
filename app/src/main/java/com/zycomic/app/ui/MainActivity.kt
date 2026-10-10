package com.zycomic.app.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import cafe.adriel.voyager.navigator.Navigator
import com.zycomic.app.data.repository.UserRepository
import com.zycomic.app.ui.browse.BrowseScreen
import com.zycomic.app.ui.browse.BrowseViewModel
import com.zycomic.app.ui.history.HistoryScreen
import com.zycomic.app.ui.history.HistoryViewModel
import com.zycomic.app.ui.library.LibraryScreen
import com.zycomic.app.ui.library.LibraryViewModel
import com.zycomic.app.ui.login.LoginOverlay
import com.zycomic.app.ui.manga.MangaDetailOverlay
import com.zycomic.app.ui.profile.ProfileScreen
import com.zycomic.app.ui.search.SearchOverlay
import com.zycomic.app.ui.settings.AboutScreen
import com.zycomic.app.ui.settings.DataStorageScreen
import com.zycomic.app.ui.settings.SettingsMainScreen
import com.zycomic.app.ui.settings.SettingsViewModel
import com.zycomic.app.ui.settings.SpeedTestScreen
import com.zycomic.app.ui.settings.TagBlockScreen
import com.zycomic.app.ui.settings.ZYSettingsAppearanceScreen
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.settings.screen.SettingsReaderScreen
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.tachiyomi.ui.base.delegate.ThemingDelegate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MainActivity : BaseActivity() {

    // 最近一次由桌面快捷方式请求打开的底部 tab；-1 表示普通启动/已消费，不改变默认页。
    private var shortcutTab by mutableIntStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 根据 UiPreferences 设置 Activity XML 主题（由 BaseActivity.onCreate 统一处理）

        // 桌面快捷方式冷启动直达对应底部 tab
        shortcutTab = intent?.shortcutTab() ?: -1

        com.zycomic.app.net.RouteManager.applyDefaultConfig()
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
                    AppContent(
                        isLaunch = savedInstanceState == null,
                        shortcutTab = shortcutTab,
                        onShortcutTabConsumed = { shortcutTab = -1 },
                    )
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
    Appearance, Reader, TagBlock, SpeedTest, DataStorage, About
}

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
    // 热启动点击快捷方式：切到目标 tab 后消费，避免重组/旋转误跳
    LaunchedEffect(shortcutTab) {
        if (shortcutTab in 0..3) {
            bottomTab = shortcutTab
            onShortcutTabConsumed()
        }
    }

    // 覆盖层状态
    var detailBookId by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var loginOpen by remember { mutableStateOf(false) }

    var searchKeyword by remember { mutableStateOf("") }
    var pendingTag by remember { mutableStateOf<String?>(null) }

    // 共享的设置 ViewModel（启动测速 + 设置页共用）
    val settingsVm = remember { SettingsViewModel() }
    var speedTesting by remember { mutableStateOf(isLaunch && !com.zycomic.app.ui.settings.SettingsViewModel.autoTestDone) }

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
            android.util.Log.d("SpeedTest", "speed test launched")
            val result = withTimeoutOrNull(8000) { settingsVm.autoSelectFastest() }
            com.zycomic.app.ui.settings.SettingsViewModel.autoTestDone = true
            speedTesting = false
            when {
                result == null -> { android.util.Log.d("SpeedTest", "speed test timeout"); Toast.makeText(context, "测速超时，使用当前线路", Toast.LENGTH_SHORT).show() }
                result.first == -1 -> Toast.makeText(context, "测速失败，使用当前线路", Toast.LENGTH_SHORT).show()
                else -> { android.util.Log.d("SpeedTest", "speed test result line=${result.first} img=${result.second}"); Toast.makeText(context, "已选择线路${result.first + 1} + 图源${result.second + 1}", Toast.LENGTH_SHORT).show() }
            }
        } else {
            speedTesting = false
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
        if (!com.zycomic.app.util.LoginGate.ensureLogin(context)) loginOpen = true else action()
    }
    val openTagBlock = { requireLogin { settingsDialog = SettingsDialog.TagBlock } }
    val openSpeedTest = { settingsDialog = SettingsDialog.SpeedTest }
    val openDataStorage = { settingsDialog = SettingsDialog.DataStorage }
    val openAbout = { settingsDialog = SettingsDialog.About }
    val blockGayTags = { requireLogin { showGayConfirm = true } }

    // 测速完成前不渲染底层页面，避免页面用默认线路发起请求；
    // 测速结束后若首启向导未完成，则先全屏进入向导，完成后才进主界面
    if (!speedTesting && onboardingDone) {
    val browseVm = remember { BrowseViewModel() }
    val libraryVm = remember { LibraryViewModel(mode = 0) }
    val historyVm = remember { HistoryViewModel() }
    androidx.activity.compose.BackHandler(
        enabled = detailBookId != null || showSearch || loginOpen || settingsDialog != null,
    ) {
        when {
            settingsDialog != null -> settingsDialog = null
            detailBookId != null -> detailBookId = null
            showSearch -> { showSearch = false }
            loginOpen -> loginOpen = false
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
                0 -> BrowseScreen(
                    vm = browseVm,
                    pendingTag = pendingTag,
                    onPendingTagConsumed = { pendingTag = null },
                    onOpenManga = { detailBookId = it },
                    onOpenSearch = { showSearch = true },
                    onRequireLogin = { loginOpen = true },
                )
                1 -> LibraryScreen(
                    vm = libraryVm,
                    onOpenManga = { detailBookId = it },
                    onRequireLogin = { loginOpen = true },
                    onOpenSearch = { kw ->
                        searchKeyword = kw
                        showSearch = true
                    },
                )
                2 -> HistoryScreen(
                    vm = historyVm,
                    onOpenManga = { detailBookId = it },
                    onRequireLogin = { loginOpen = true },
                    onOpenSearch = { kw ->
                        searchKeyword = kw
                        showSearch = true
                    },
                )
                3 -> ProfileScreen(
                    onRequireLogin = { loginOpen = true },
                    onNavigateToTab = { bottomTab = it },
                    onOpenTagBlock = openTagBlock,
                    onBlockGayTags = blockGayTags,
                )
                4 -> SettingsMainScreen(
                    onOpenAppearance = openAppearance,
                    onOpenReader = openReader,
                    onOpenSpeedTest = openSpeedTest,
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
} else if (!speedTesting) {
        // 测速已结束但首启向导未完成：全屏进入 3 页向导
        com.zycomic.app.ui.onboarding.OnboardingHost(
            settingsVm = settingsVm,
            onFinish = { onboardingDone = true },
        )
    } else {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 48.dp)) {
                Text("ZYComic", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(24.dp))
                CircularProgressIndicator(modifier = Modifier.size(64.dp), strokeWidth = 4.dp)
                Spacer(modifier = Modifier.height(24.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
                Spacer(modifier = Modifier.height(24.dp))
                Text("正在选择最快线路...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    // ---- 详情覆盖层 ----
    detailBookId?.let { bookId ->
        MangaDetailOverlay(
            bookId = bookId,
            onClose = { detailBookId = null },
            onOpenManga = { detailBookId = it },
            onSearchKeyword = { kw ->
                searchKeyword = kw
                detailBookId = null
                showSearch = true
            },
            onTagClick = { tag ->
                detailBookId = null
                bottomTab = 0
                pendingTag = tag
            },
            onRequireLogin = { loginOpen = true },
        )
    }

    // ---- 搜索覆盖层 ----
    if (showSearch) {
        SearchOverlay(
            initialKeyword = searchKeyword,
            onClose = {
                showSearch = false
                searchKeyword = ""
            },
            onOpenManga = { detailBookId = it },
        )
    }

    // ---- 登录覆盖层 ----
    if (loginOpen) {
        LoginOverlay(
            onClose = { loginOpen = false },
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
        SettingsDialog.SpeedTest -> {
            Dialog(
                onDismissRequest = { settingsDialog = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                SpeedTestScreen(vm = settingsVm, onClose = { settingsDialog = null })
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
