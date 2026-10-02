package com.zycomic.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import com.zycomic.app.data.repository.UserRepository
import com.zycomic.app.ui.browse.BrowseScreen
import com.zycomic.app.ui.library.LibraryScreen
import com.zycomic.app.ui.login.LoginScreen
import com.zycomic.app.ui.login.LoginOverlay
import com.zycomic.app.ui.manga.MangaDetailScreen
import com.zycomic.app.ui.manga.MangaDetailOverlay
import com.zycomic.app.ui.profile.ProfileScreen
import com.zycomic.app.ui.search.SearchScreen
import com.zycomic.app.ui.search.SearchOverlay
import com.zycomic.app.ui.settings.SettingsScreen
import com.zycomic.app.ui.settings.SettingsViewModel
import com.zycomic.app.ui.theme.ZYComicTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 在构建网络 client 之前应用默认配置（rule + sni）
        com.zycomic.app.net.RouteManager.applyDefaultConfig()
        setContent {
            ZYComicTheme {
                AppContent()
            }
        }

        // 启动时校验登录态；未登录则提示首次进入建议注册登录
        lifecycleScope.launch {
            val loggedIn = try {
                UserRepository.verifyLogin()
            } catch (e: Exception) {
                false
            }
            if (!loggedIn) {
                Toast.makeText(
                    this@MainActivity,
                    "首次进入建议注册登录，才能使用收藏功能",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}

/**
 * 应用根内容：底部导航 + 覆盖层状态管理。
 *
 * 覆盖层（full-screen Dialog）：
 * - detailOverlay：漫画详情
 * - searchOverlay：搜索页
 * - loginOverlay：登录页
 *
 * 阅读器已改为 komikku 原生 ReaderActivity（独立 Activity，不再是 Dialog 覆盖层）。
 *
 * 覆盖层打开时底层页面不销毁，返回时状态完全保留。
 */
@Composable
fun AppContent() {
    val context = LocalContext.current

    var bottomTab by remember { mutableIntStateOf(0) } // 0分类 1书架 2我的 3设置

    // 覆盖层状态
    var detailBookId by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var loginOpen by remember { mutableStateOf(false) }

    // 从详情带关键词跳到搜索 / 从详情带标签回到分类页
    var searchKeyword by remember { mutableStateOf("") }
    var pendingTag by remember { mutableStateOf<String?>(null) }

    // 启动自动测速：测速期间全屏加载层覆盖
    val speedTestVm = remember { SettingsViewModel() }
    var speedTesting by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val (li, ii) = speedTestVm.autoSelectFastest()
        speedTesting = false
        if (li == -1 && ii == -1) {
            Toast.makeText(context, "测速失败，使用当前线路", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "已选择线路${li + 1} + 图源${ii + 1}", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
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
                    icon = { Icon(Icons.Default.Person, contentDescription = "我的") },
                    label = { Text("我的") },
                )
                NavigationBarItem(
                    selected = bottomTab == 3,
                    onClick = { bottomTab = 3 },
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
                )
                2 -> ProfileScreen(
                    onRequireLogin = { loginOpen = true },
                )
                3 -> SettingsScreen()
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
