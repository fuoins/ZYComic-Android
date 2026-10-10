package com.zycomic.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import com.zycomic.app.data.repository.UserRepository
import com.zycomic.app.ui.browse.BrowseGuideContent
import com.zycomic.app.ui.browse.GuideActionButton
import com.zycomic.app.ui.browse.GuideButtonTone
import com.zycomic.app.ui.browse.GuideIcon
import com.zycomic.app.ui.login.LoginScreen
import com.zycomic.app.ui.login.LoginViewModel
import com.zycomic.app.ui.settings.SettingsViewModel
import com.zycomic.app.ui.settings.ZYSettingsAppearanceScreen
import com.zycomic.app.util.LoginGate
import eu.kanade.presentation.util.LocalBackPress
import kotlinx.coroutines.launch

/**
 * 首启引导：测速结束后、首次进入时全屏覆盖主界面，走完「开始使用」才进入。
 *
 * 三页（HorizontalPager 可左右滑 + 底部上一页/下一页）：
 * 0 外观（整块嵌入现有外观设置页）；1 登录（可暂不登录）；2 使用说明（图标化，含真实一键屏蔽）。
 */
@Composable
fun OnboardingHost(
    settingsVm: SettingsViewModel,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })
    val loginVm = remember { LoginViewModel() }

    // 返回键：第 2/3 页回上一页，第 1 页拦截不退出（未完成不得离开向导）
    BackHandler(enabled = true) {
        if (pagerState.currentPage > 0) {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                when (page) {
                    0 -> AppearancePage()
                    1 -> LoginPage(vm = loginVm, onNext = { scope.launch { pagerState.animateScrollToPage(2) } })
                    else -> GuidePage(
                        settingsVm = settingsVm,
                        onRequireLogin = { scope.launch { pagerState.animateScrollToPage(1) } },
                    )
                }
            }
            OnboardingBottomBar(
                page = pagerState.currentPage,
                onBack = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                onNext = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                onFinish = {
                    OnboardingPrefs.setDone(context, true)
                    onFinish()
                },
            )
        }
    }
}

/** 第1页：整块嵌入现有外观设置（只剩主题项）；根级隐藏返回箭头，子页保留返回。 */
@Composable
private fun AppearancePage() {
    Navigator(screen = ZYSettingsAppearanceScreen) { navigator ->
        val back: (() -> Unit)? = if (navigator.canPop) ({ navigator.pop() }) else null
        androidx.compose.runtime.CompositionLocalProvider(LocalBackPress provides back) {
            navigator.lastItem.Content()
        }
    }
}

/** 第2页：整块嵌入登录；登录成功自动下一页，底部另有「暂不登录」。 */
@Composable
private fun LoginPage(vm: LoginViewModel, onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        // 登录成功时 LoginScreen 内部会回调 onClose()；这里接到“进入下一页”
        LoginScreen(vm = vm, onClose = onNext)
    }
}

/** 第3页：图标化使用说明 + 真实可点的一键屏蔽gay标签按钮。 */
@Composable
private fun GuidePage(
    settingsVm: SettingsViewModel,
    onRequireLogin: () -> Unit,
) {
    val context = LocalContext.current
    val user by UserRepository.userFlow.collectAsState()
    val isLoggedIn = user != null
    val blocked by settingsVm.blockedTags.collectAsState()
    val toast by settingsVm.toast.collectAsState()
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (UserRepository.isLoggedIn) settingsVm.loadBlockedTags()
    }
    // 成功/失败都会发 toast，据此结束 loading
    LaunchedEffect(toast) {
        if (toast != null) busy = false
    }

    val gayDone = remember(blocked) { SettingsViewModel.GAY_TAGS.all { it in blocked } }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(8.dp))
            Text("使用说明", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        BrowseGuideContent(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            maxHeight = null,
            gayBlockSlot = {
                Spacer(Modifier.height(6.dp))
                when {
                    gayDone -> GuideActionButton(
                        text = "已开启gay标签屏蔽",
                        icon = GuideIcon.Vec(Icons.Filled.DoneAll),
                        onClick = {},
                        tone = GuideButtonTone.Success,
                    )
                    !isLoggedIn -> GuideActionButton(
                        text = "登录后一键屏蔽gay标签",
                        icon = GuideIcon.Vec(Icons.Outlined.Login),
                        onClick = {
                            // 未登录：统一 Toast 并跳回登录页，不执行屏蔽
                            if (!LoginGate.ensureLogin(context)) onRequireLogin()
                        },
                        tone = GuideButtonTone.Neutral,
                    )
                    else -> GuideActionButton(
                        text = "一键屏蔽gay标签（${SettingsViewModel.GAY_TAGS.size}个）",
                        icon = GuideIcon.Vec(Icons.Outlined.Block),
                        onClick = {
                            busy = true
                            settingsVm.blockGayTags()
                        },
                        tone = GuideButtonTone.Primary,
                        loading = busy,
                    )
                }
            },
        )
    }
}

@Composable
private fun OnboardingBottomBar(
    page: Int,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (page > 0) TextButton(onClick = onBack) { Text("上一页") }
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) { i ->
                    val color = if (i == page) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                }
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                when (page) {
                    0 -> Button(onClick = onNext) { Text("下一页") }
                    1 -> OutlinedButton(onClick = onNext) { Text("暂不登录") }
                    else -> Button(onClick = onFinish) { Text("开始使用") }
                }
            }
        }
    }
}
