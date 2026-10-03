package com.zycomic.app.ui

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.zycomic.app.ui.history.HistoryScreen
import com.zycomic.app.ui.library.LibraryScreen
import com.zycomic.app.ui.login.LoginOverlay
import com.zycomic.app.ui.manga.MangaDetailOverlay
import com.zycomic.app.ui.profile.ProfileScreen
import com.zycomic.app.ui.search.SearchOverlay
import com.zycomic.app.ui.settings.AboutScreen
import com.zycomic.app.ui.settings.AdvancedSettingsScreen
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
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 根据 UiPreferences 设置 Activity XML 主题（浅色/深色/AMOLED）
        val uiPreferences = Injekt.get<UiPreferences>()
        ThemingDelegate.getThemeResIds(
            uiPreferences.appTheme().get(),
            uiPreferences.themeDarkAmoled().get(),
        ).forEach { setTheme(it) }

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
                    AppContent()
                }
            }
        }

        // 登录态已在 App.kt 启动时处理（本地恢复+后台刷新），这里只检查未登录提示
        if (!UserRepository.isLoggedIn) {
            Toast.makeText(
                this@MainActivity,
                "首次进入建议注册登录，才能使用收藏功能",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

/** 设置子页面 Dialog 标识 */
private enum class SettingsDialog {
    Appearance, Reader, TagBlock, SpeedTest, DataStorage, Advanced, About
}

@Composable
fun AppContent() {
    val context = LocalContext.current

    // edge-to-edge 系统栏适配：根据背景亮度决定状态栏图标明暗
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val statusBarBg = MaterialTheme.colorScheme.surface
    LaunchedEffect(isSystemInDarkTheme, statusBarBg) {
        val lightStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.BLACK)
        val darkStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        (context as ComponentActivity).enableEdgeToEdge(
            statusBarStyle = if (statusBarBg.luminance() > 0.5f) lightStyle else darkStyle,
            navigationBarStyle = if (isSystemInDarkTheme) darkStyle else lightStyle,
        )
    }

    var bottomTab by remember { mutableIntStateOf(0) } // 0分类 1书架 2历史 3我的 4设置

    // 覆盖层状态
    var detailBookId by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var loginOpen by remember { mutableStateOf(false) }

    var searchKeyword by remember { mutableStateOf("") }
    var pendingTag by remember { mutableStateOf<String?>(null) }

    // 共享的设置 ViewModel（启动测速 + 设置页共用）
    val settingsVm = remember { SettingsViewModel() }
    var speedTesting by remember { mutableStateOf(true) }

    // 设置子页面 Dialog 状态
    var settingsDialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var showGayConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (li, ii) = settingsVm.autoSelectFastest()
        speedTesting = false
        if (li == -1 && ii == -1) {
            Toast.makeText(context, "测速失败，使用当前线路", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "已选择线路${li + 1} + 图源${ii + 1}", Toast.LENGTH_SHORT).show()
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
    val openTagBlock = { settingsDialog = SettingsDialog.TagBlock }
    val openSpeedTest = { settingsDialog = SettingsDialog.SpeedTest }
    val openDataStorage = { settingsDialog = SettingsDialog.DataStorage }
    val openAdvanced = { settingsDialog = SettingsDialog.Advanced }
    val openAbout = { settingsDialog = SettingsDialog.About }
    val blockGayTags = { showGayConfirm = true }

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
                    pendingTag = pendingTag,
                    onPendingTagConsumed = { pendingTag = null },
                    onOpenManga = { detailBookId = it },
                    onOpenSearch = { showSearch = true },
                    onRequireLogin = { loginOpen = true },
                )
                1 -> LibraryScreen(
                    onOpenManga = { detailBookId = it },
                    onRequireLogin = { loginOpen = true },
                    onOpenSearch = {
                        searchKeyword = ""
                        showSearch = true
                    },
                )
                2 -> HistoryScreen(
                    onOpenManga = { detailBookId = it },
                    onRequireLogin = { loginOpen = true },
                )
                3 -> ProfileScreen(
                    onRequireLogin = { loginOpen = true },
                    onNavigateToTab = { bottomTab = it },
                    onOpenAppearance = openAppearance,
                    onOpenReader = openReader,
                    onOpenTagBlock = openTagBlock,
                    onBlockGayTags = blockGayTags,
                    onOpenSpeedTest = openSpeedTest,
                    onOpenDataStorage = openDataStorage,
                    onOpenAdvanced = openAdvanced,
                    onOpenAbout = openAbout,
                )
                4 -> SettingsMainScreen(
                    onOpenAppearance = openAppearance,
                    onOpenReader = openReader,
                    onOpenTagBlock = openTagBlock,
                    onBlockGayTags = blockGayTags,
                    onOpenSpeedTest = openSpeedTest,
                    onOpenDataStorage = openDataStorage,
                    onOpenAdvanced = openAdvanced,
                    onOpenAbout = openAbout,
                )
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
        SettingsDialog.Advanced -> {
            Dialog(
                onDismissRequest = { settingsDialog = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                AdvancedSettingsScreen(
                    vm = settingsVm,
                    onClose = { settingsDialog = null },
                    onOpenSpeedTest = { settingsDialog = SettingsDialog.SpeedTest },
                )
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

    // ---- 启动测速全屏加载层 ----
    if (speedTesting) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                    Text("正在测速选择最快线路...", color = Color.White)
                }
            }
        }
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
