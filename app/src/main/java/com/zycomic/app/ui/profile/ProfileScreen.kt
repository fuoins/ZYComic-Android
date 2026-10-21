package com.zycomic.app.ui.profile

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zycomic.app.data.dto.SignDay
import com.zycomic.app.data.dto.WelfareData
import com.zycomic.app.data.repository.JmCheckInRepository
import com.zycomic.app.data.repository.ManwaSessionStatus
import com.zycomic.app.data.repository.UserRepository
import com.zycomic.app.net.JmAuthStore
import com.zycomic.app.net.JmNetworkModule
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.ContentPlatformPrefs
import com.zycomic.app.platform.ContentPlatformSelector
import com.zycomic.app.platform.StartupPlatformPolicy
import com.zycomic.app.ui.login.JmAccountLogin
import com.zycomic.app.ui.login.JmPasswordChangeDialog
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.i18n.stringResource
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onRequireLogin: () -> Unit,
    onNavigateToTab: (Int) -> Unit,
    onOpenManga: (String) -> Unit = {},
    isDetailOpen: Boolean = false,
    onOpenTagBlock: () -> Unit,
    onOpenBookBlock: () -> Unit,
    onBlockGayTags: () -> Unit,
) {
    val context = LocalContext.current
    var platform by remember { mutableStateOf(ContentPlatformPrefs.load("profile")) }
    val selectPlatform: (ContentPlatform) -> Unit = { selected ->
        if (StartupPlatformPolicy.checkSelection(context, selected)) {
            platform = selected
            ContentPlatformPrefs.save("profile", selected)
        }
    }
    val user by UserRepository.userFlow.collectAsState()
    val manwaSessionStatus by UserRepository.sessionStatusFlow.collectAsState()
    val jmProfile by JmAuthStore.profileFlow.collectAsState()
    val picacgLoggedIn by com.zycomic.app.picacg.PicacgAuthStore.loggedIn.collectAsState()
    var picacgProfileRefresh by remember { mutableStateOf(0) }
    var picacgRefreshing by remember { mutableStateOf(false) }
    val jmSnapshotNotice = stringResource(KMR.strings.jm_account_snapshot_notice)
    val manwaRefreshSuccess = stringResource(KMR.strings.manwa_profile_refresh_verified)
    val manwaRefreshUnknown = stringResource(KMR.strings.manwa_session_unknown)
    val manwaSessionExpired = stringResource(KMR.strings.manwa_session_expired)
    val jmRefreshSuccess = stringResource(KMR.strings.jm_profile_refresh_success)
    val jmRefreshFailed = stringResource(KMR.strings.jm_profile_refresh_failed)
    var jmRefreshing by remember { mutableStateOf(false) }
    suspend fun refreshJm() {
        if (jmRefreshing) return
        jmRefreshing = true
        try {
            JmAccountLogin.refreshSavedAccountCredentials()
            Toast.makeText(context, jmRefreshSuccess, Toast.LENGTH_SHORT).show()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Toast.makeText(context, error.message ?: jmRefreshFailed, Toast.LENGTH_LONG).show()
        } finally {
            jmRefreshing = false
        }
    }
    // A restored profile stays visible while its first verification is pending, including the
    // first frame before LaunchedEffect starts. Each account/platform owns its own UI state.
    var manwaRefreshing by remember(platform, user?.uid) {
        mutableStateOf(platform == ContentPlatform.MANWA && user != null)
    }
    suspend fun refreshManwa(showResult: Boolean) {
        manwaRefreshing = true
        try {
            UserRepository.refreshUserInfo()
            if (showResult) {
                val message = when (UserRepository.sessionStatusFlow.value) {
                    ManwaSessionStatus.VERIFIED -> manwaRefreshSuccess
                    ManwaSessionStatus.EXPIRED -> manwaSessionExpired
                    else -> manwaRefreshUnknown
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (showResult) {
                val message = if (UserRepository.sessionStatusFlow.value == ManwaSessionStatus.EXPIRED) {
                    manwaSessionExpired
                } else {
                    manwaRefreshUnknown
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        } finally {
            manwaRefreshing = false
        }
    }
    val jmAccount by JmAuthStore.accountNameFlow.collectAsState()
    val jmLoggedIn by JmAuthStore.loggedInFlow.collectAsState()
    val jmSessionRevision by JmAuthStore.sessionRevisionFlow.collectAsState()
    var showJmMyComments by remember { mutableStateOf(false) }
    var showJmCalendar by remember { mutableStateOf(false) }
    var showJmMakeup by remember { mutableStateOf(false) }
    var showJmAwards by remember { mutableStateOf(false) }
    var jmSubscreenSessionRevision by remember { mutableStateOf<Long?>(null) }
    val jmCheckInViewModel = remember(jmSessionRevision) { JmCheckInViewModel() }
    DisposableEffect(jmCheckInViewModel) {
        onDispose { jmCheckInViewModel.close() }
    }
    LaunchedEffect(jmSessionRevision) {
        jmCheckInViewModel.cancelMakeup()
        showJmCalendar = false
        showJmMakeup = false
        showJmAwards = false
        jmSubscreenSessionRevision = null
    }
    fun closeCalendar() {
        jmCheckInViewModel.cancelMakeup()
        showJmCalendar = false
        jmSubscreenSessionRevision = null
    }
    fun closeMakeup() {
        jmCheckInViewModel.cancelMakeup()
        showJmMakeup = false
        jmSubscreenSessionRevision = null
    }
    LaunchedEffect(platform, user?.uid) {
        if (platform != ContentPlatform.JM) closeMakeup()
        if (platform == ContentPlatform.MANWA && StartupPlatformPolicy.isEnabled(platform) && user != null) {
            refreshManwa(showResult = false)
        } else {
            manwaRefreshing = false
        }
    }
    var showPointLogs by remember { mutableStateOf(false) }
    var showJmChangePwd by remember { mutableStateOf(false) }
    var showChangePwd by remember { mutableStateOf(false) }
    var showRewardDialog by remember { mutableStateOf(false) }
    var showAdDebug by remember { mutableStateOf<String?>(null) }
    var adProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    androidx.activity.compose.BackHandler(enabled = showPointLogs) { showPointLogs = false }
    androidx.activity.compose.BackHandler(enabled = showJmCalendar) { closeCalendar() }
    androidx.activity.compose.BackHandler(enabled = showJmMakeup) { closeMakeup() }
    androidx.activity.compose.BackHandler(enabled = showJmAwards) { showJmAwards = false }
    androidx.activity.compose.BackHandler(enabled = showChangePwd) { showChangePwd = false }
    androidx.activity.compose.BackHandler(enabled = showRewardDialog) { showRewardDialog = false }
    androidx.activity.compose.BackHandler(enabled = showAdDebug != null) { showAdDebug = null }
    androidx.activity.compose.BackHandler(enabled = adProgress != null) { adProgress = null }
    var adMsg by remember { mutableStateOf("") }
    var adDone by remember { mutableStateOf(false) }
    var adResultOk by remember { mutableStateOf<Int?>(null) }
    var adErr by remember { mutableStateOf<String?>(null) }
    var rewarding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showJmCalendar && jmLoggedIn && jmSubscreenSessionRevision == jmSessionRevision && platform == ContentPlatform.JM) {
        JmCalendarDetailScreen(jmCheckInViewModel, ::closeCalendar)
        return
    }
    if (showJmMakeup && jmLoggedIn && jmSubscreenSessionRevision == jmSessionRevision && platform == ContentPlatform.JM) {
        JmMakeupScreen(jmCheckInViewModel, context, ::closeMakeup)
        return
    }
    if (showJmAwards && jmLoggedIn && jmSubscreenSessionRevision == jmSessionRevision && platform == ContentPlatform.JM) {
        JmAnnualAwardsScreen(jmCheckInViewModel) { showJmAwards = false }
        return
    }
    if (showJmMyComments && platform == ContentPlatform.JM) {
        JmMyCommentsScreen(
            onBack = { showJmMyComments = false },
            onRequireLogin = onRequireLogin,
            onOpenManga = onOpenManga,
            isDetailOpen = isDetailOpen,
        )
        return
    }

    // KMK --> JM check-in runs only while this profile is entered, never as a background worker.
    var jmAutoCheckIn by remember(context) { mutableStateOf(JmCheckInRepository.isAutoEnabled(context)) }
    var jmCheckingIn by remember { mutableStateOf(false) }
    val jmToday = LocalDate.now().toString()
    var jmCheckInMessage by remember(platform, jmLoggedIn, jmAccount, jmSessionRevision, jmToday) {
        mutableStateOf(
            if (platform == ContentPlatform.JM && jmLoggedIn) {
                JmCheckInRepository.todaySuccessMessage(context, jmToday)
            } else {
                null
            },
        )
    }
    val jmCheckInFailure = stringResource(KMR.strings.jm_check_in_failed)
    suspend fun checkInJm(automatic: Boolean) {
        if (jmCheckingIn) return
        jmCheckingIn = true
        try {
            val result = JmCheckInRepository.checkIn(context, automatic)
            jmCheckInMessage = result ?: JmCheckInRepository.todaySuccessMessage(context, jmToday) ?: jmCheckInMessage
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            jmCheckInMessage = error.message ?: jmCheckInFailure
        } finally {
            jmCheckingIn = false
        }
    }
    LaunchedEffect(platform, jmLoggedIn, jmAccount, jmSessionRevision, jmAutoCheckIn, jmToday) {
        if (platform == ContentPlatform.JM && StartupPlatformPolicy.isEnabled(platform) && jmLoggedIn && jmAutoCheckIn) {
            checkInJm(automatic = true)
        }
    }
    // KMK <--

    tachiyomi.presentation.core.components.material.Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { contentPadding ->
        ScrollbarLazyColumn(contentPadding = contentPadding) {
            item {
                LogoHeader {
                    TextButton(
                        enabled = when (platform) {
                            ContentPlatform.MANWA -> user != null && !manwaRefreshing
                            ContentPlatform.PICACG -> picacgLoggedIn && !picacgRefreshing
                            ContentPlatform.JM -> jmLoggedIn && !jmRefreshing
                        },
                        onClick = {
                            when (platform) {
                                ContentPlatform.MANWA -> if (!manwaRefreshing) scope.launch { refreshManwa(showResult = true) }
                                ContentPlatform.PICACG -> picacgProfileRefresh++
                                ContentPlatform.JM -> if (!jmRefreshing) scope.launch { refreshJm() }
                            }
                        },
                    ) {
                        if ((platform == ContentPlatform.MANWA && manwaRefreshing) ||
                            (platform == ContentPlatform.PICACG && picacgRefreshing) ||
                            (platform == ContentPlatform.JM && jmRefreshing)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(KMR.strings.profile_refresh))
                    }
                }
            }

            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(stringResource(KMR.strings.content_platform_section_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(KMR.strings.content_platform_profile_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                    ContentPlatformSelector(
                        selected = platform,
                        onSelect = selectPlatform,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ---- 用户信息 ----
            item {
                if (platform == ContentPlatform.PICACG) {
                    com.zycomic.app.ui.picacg.PicacgAccountProfileContent(
                        onRequireLogin = onRequireLogin,
                        refreshRequest = picacgProfileRefresh,
                        onBusyChanged = { picacgRefreshing = it },
                    )
                } else if (platform == ContentPlatform.JM) {
                    TextPreferenceWidget(
                        title = jmAccount.takeIf { jmLoggedIn } ?: stringResource(KMR.strings.jm_account_title),
                        subtitle = if (jmLoggedIn) {
                            stringResource(
                                KMR.strings.jm_account_stats,
                                jmProfile?.level ?: "—",
                                jmProfile?.exp ?: "—",
                                jmProfile?.nextLevelExp ?: "—",
                                jmProfile?.coin ?: "—",
                                jmProfile?.albumFavorites ?: "—",
                                jmProfile?.albumFavoritesMax ?: "—",
                            )
                        } else {
                            stringResource(KMR.strings.jm_account_signed_out)
                        },
                        icon = if (!jmLoggedIn) Icons.Outlined.Login else Icons.Outlined.Info,
                        onPreferenceClick = {
                            if (!jmLoggedIn) onRequireLogin() else Toast.makeText(context, jmSnapshotNotice, Toast.LENGTH_LONG).show()
                        },
                    )
                } else if (user == null) {
                    TextPreferenceWidget(
                        title = "点击登录",
                        subtitle = if (manwaSessionStatus == ManwaSessionStatus.EXPIRED) manwaSessionExpired else "登录后可使用收藏、评论等功能",
                        icon = Icons.Outlined.Login,
                        onPreferenceClick = onRequireLogin,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${user!!.nickname.firstOrNull() ?: "?"}",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                            Text(
                                user!!.nickname.ifBlank { user!!.username.ifBlank { "未登录" } },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                "Lv${user!!.level}  收藏${user!!.favoriteCount}  积分${pointLimitText(user!!.level, user!!.point)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (ManwaProfileUiPolicy.showUnverifiedWarning(manwaSessionStatus, manwaRefreshing)) {
                                Text(
                                    manwaRefreshUnknown,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // KMK --> JM preferences remain visible while signed out; network calls require login.
            if (platform == ContentPlatform.JM) {
                item {
                    TextPreferenceWidget(
                        title = stringResource(KMR.strings.jm_calendar_title),
                        subtitle = stringResource(KMR.strings.jm_calendar_summary),
                        icon = Icons.Outlined.CalendarMonth,
                        onPreferenceClick = {
                            if (jmLoggedIn) {
                                jmSubscreenSessionRevision = jmSessionRevision
                                showJmCalendar = true
                            } else {
                                onRequireLogin()
                            }
                        },
                    )
                }
                item {
                    TextPreferenceWidget(
                        title = stringResource(KMR.strings.jm_makeup_title),
                        subtitle = stringResource(KMR.strings.jm_makeup_description),
                        icon = Icons.Outlined.EditCalendar,
                        onPreferenceClick = {
                            if (jmLoggedIn) {
                                jmSubscreenSessionRevision = jmSessionRevision
                                showJmMakeup = true
                            } else {
                                onRequireLogin()
                            }
                        },
                    )
                }
                item {
                    TextPreferenceWidget(
                        title = stringResource(KMR.strings.jm_annual_awards_title),
                        subtitle = "按年份浏览月度奖励图片，不会领取月奖",
                        icon = Icons.Filled.Star,
                        onPreferenceClick = {
                            if (jmLoggedIn) {
                                jmSubscreenSessionRevision = jmSessionRevision
                                showJmAwards = true
                            } else {
                                onRequireLogin()
                            }
                        },
                    )
                }
                item {
                    TextPreferenceWidget(
                        title = stringResource(KMR.strings.jm_my_comments_title),
                        icon = Icons.Outlined.Info,
                        onPreferenceClick = { showJmMyComments = true },
                    )
                }
                item {
                    SwitchPreferenceWidget(
                        title = stringResource(KMR.strings.jm_auto_check_in),
                        subtitle = stringResource(KMR.strings.jm_auto_check_in_summary),
                        icon = Icons.Outlined.EventRepeat,
                        checked = jmAutoCheckIn,
                        onCheckedChanged = { enabled ->
                            JmCheckInRepository.setAutoEnabled(context, enabled)
                            jmAutoCheckIn = enabled
                        },
                    )
                }
                item {
                    TextPreferenceWidget(
                        title = stringResource(if (jmCheckingIn) KMR.strings.jm_check_in_working else KMR.strings.jm_manual_check_in),
                        subtitle = jmCheckInMessage,
                        icon = Icons.Outlined.EventAvailable,
                        onPreferenceClick = {
                            if (!jmLoggedIn) {
                                onRequireLogin()
                            } else if (!jmCheckingIn) {
                                scope.launch { checkInJm(automatic = false) }
                            }
                        },
                    )
                }
            }
            // KMK <--

            if (platform == ContentPlatform.JM && jmLoggedIn) {
                item {
                    TextPreferenceWidget(
                        title = stringResource(KMR.strings.jm_change_password),
                        icon = Icons.Outlined.Lock,
                        onPreferenceClick = { showJmChangePwd = true },
                    )
                }
                item {
                    TextPreferenceWidget(
                        title = stringResource(KMR.strings.jm_logout),
                        icon = Icons.Outlined.Logout,
                        onPreferenceClick = {
                            JmAuthStore.clear()
                            JmNetworkModule.clearCookies()
                        },
                    )
                }
            }

            // ---- 积分明细 / 退出 ----
            if (platform == ContentPlatform.MANWA && user != null) {
                item {
                    TextPreferenceWidget(
                        title = "积分明细(仔细看等级划分)",
                        icon = Icons.Filled.Star,
                        onPreferenceClick = { showPointLogs = true },
                    )
                }
                item {
                    TextPreferenceWidget(
                        title = if (rewarding) "领取中..." else "每日奖励破解领取",
                        icon = Icons.Outlined.Code,
                        onPreferenceClick = { if (!rewarding) showRewardDialog = true },
                    )
                }
            }

            if (platform == ContentPlatform.MANWA) {
                item {
                    TextPreferenceWidget(
                        title = "标签屏蔽",
                        icon = Icons.AutoMirrored.Outlined.Label,
                        onPreferenceClick = onOpenTagBlock,
                    )
                }
                item {
                    TextPreferenceWidget(
                        title = "漫画屏蔽",
                        icon = Icons.Outlined.VisibilityOff,
                        onPreferenceClick = onOpenBookBlock,
                    )
                }
                item {
                    TextPreferenceWidget(
                        title = "gay标签一键屏蔽",
                        icon = Icons.Outlined.Block,
                        onPreferenceClick = onBlockGayTags,
                    )
                }
            }

            // Account actions follow all feature rows; no bottom account divider.
            if (platform == ContentPlatform.MANWA && user != null) {
                item {
                    TextPreferenceWidget(
                        title = "修改密码",
                        icon = Icons.Outlined.Lock,
                        onPreferenceClick = { showChangePwd = true },
                    )
                }
                item {
                    TextPreferenceWidget(
                        title = "退出登录",
                        icon = Icons.Outlined.Logout,
                        onPreferenceClick = {
                            scope.launch { UserRepository.logout() }
                        },
                    )
                }
            }
        }
    }

    if (showPointLogs) {
        PointLogsOverlay(onClose = { showPointLogs = false })
    }

    if (showJmChangePwd) {
        JmPasswordChangeDialog(onDismiss = { showJmChangePwd = false })
    }

    if (showChangePwd) {
        var pwd1 by remember { mutableStateOf("") }
        var pwd2 by remember { mutableStateOf("") }
        var pwdErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showChangePwd = false },
            title = { Text("修改密码") },
            text = {
                Column {
                    OutlinedTextField(value = pwd1, onValueChange = { pwd1 = it }, label = { Text("新密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(value = pwd2, onValueChange = { pwd2 = it }, label = { Text("确认新密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    pwdErr?.let { Text(it, color = Color(0xFFE53935)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pwd1.isEmpty() || pwd1 != pwd2) {
                        pwdErr = "两次密码不一致"
                        return@TextButton
                    }
                    scope.launch {
                        try {
                            UserRepository.changePassword(pwd1)
                            showChangePwd = false
                            Toast.makeText(context, "密码修改成功，请重新登录", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            pwdErr = e.message ?: "修改失败"
                        }
                    }
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showChangePwd = false }) { Text("取消") } },
        )
    }

    if (showRewardDialog) {
        AlertDialog(
            onDismissRequest = { showRewardDialog = false },
            title = { Text("奖励破解领取") },
            text = { Text("确认领取今日广告奖励？") },
            confirmButton = {
                Button(
                    enabled = !rewarding,
                    onClick = {
                        scope.launch {
                            rewarding = true
                            showRewardDialog = false
                            adProgress = 0 to 5
                            adMsg = "准备中..."
                            adDone = false
                            runCatching {
                                UserRepository.claimAdBonus { c, t, m ->
                                    adProgress = c to t
                                    adMsg = m
                                }
                            }.onSuccess {
                                adResultOk = it
                            }.onFailure {
                                if (it is UserRepository.AdDebugException) {
                                    showAdDebug = it.debug
                                } else {
                                    adErr = it.message ?: "领取失败"
                                }
                            }
                            adDone = true
                            rewarding = false
                        }
                    },
                ) {
                    if (rewarding) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        Text("确认")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showRewardDialog = false }) { Text("取消") }
            },
        )
    }

    showAdDebug?.let { dbg ->
        AlertDialog(
            onDismissRequest = { showAdDebug = null },
            title = { Text("调试信息") },
            text = { OutlinedTextField(value = dbg, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth().height(200.dp), textStyle = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { showAdDebug = null }) { Text("关闭") } },
        )
    }

    adProgress?.let { (c, t) ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("奖励破解中") },
            text = {
                Column {
                    LinearProgressIndicator({ if (t > 0) c.toFloat() / t else 0f }, modifier = Modifier.fillMaxWidth().height(6.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(adMsg, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = adDone,
                    onClick = {
                        adResultOk?.let { Toast.makeText(context, "领取成功，+${it}积分", Toast.LENGTH_SHORT).show() }
                        adErr?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                        adProgress = null
                        adResultOk = null
                        adErr = null
                    },
                ) { Text("完成") }
            },
        )
    }
}

@Composable
private fun PointLogsOverlay(onClose: () -> Unit) {
    val vm = remember { PointLogsViewModel() }
    val logs by vm.logs.collectAsState()
    val loading by vm.loading.collectAsState()
    val welfare by vm.welfare.collectAsState()
    val welfareLoading by vm.welfareLoading.collectAsState()
    val welfareError by vm.welfareError.collectAsState()

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
            // 顶部标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("积分明细", style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()

            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                // 1. 等级划分（可展开，默认收起）
                item { LevelExpandSection() }

                // 2. 签到日历
                item { SignCalendarSection(welfare = welfare, loading = welfareLoading, error = welfareError, onReload = { month -> vm.loadWelfare(month) }) }

                // 3. 积分明细标题
                item {
                    Text(
                        "积分明细",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                    )
                    HorizontalDivider()
                }

                // 4. 积分明细列表
                if (loading) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.CircularProgressIndicator()
                        }
                    }
                } else {
                    items(logs.size) { i ->
                        val l = logs[i]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(l.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    l.create_time,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (l.point >= 0) "+${l.point}" else "${l.point}",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (l.point >= 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    }
                }

                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

/** 等级划分可展开区域，默认收起。 */
@Composable
private fun LevelExpandSection() {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "等级划分",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text("★等级功能:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            listOf(
                "积分300解锁编辑昵称和头像",
                "Lv1: 收藏数量300本",
                "Lv2: 收藏数量800本",
                "Lv3: 漫画章节抢先看、可额外观看隐藏漫画。收藏数量1500本",
                "Lv4: 收藏数量2500本",
                "Lv5: 收藏数量3500本",
            ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 1.dp)) }
            Spacer(Modifier.height(8.dp))
            Text("★签到规则(登录就算签到):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            listOf(
                "·每日签到可以领10积分",
                "·连续不中断签到第7天可领30积分",
                "·连续不中断签到第14天可领40积分",
                "·连续不中断签到第30天可领70积分",
                "·满30天后重新再累积计算",
                "·一天没签到扣10积分",
            ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 1.dp)) }
            Spacer(Modifier.height(8.dp))
            Text("★每日奖励(已破解点击就领):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            Text("·每日完成奖励任务可领10积分", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

/** 签到日历区域。 */
@Composable
private fun SignCalendarSection(
    welfare: WelfareData?,
    loading: Boolean,
    error: String?,
    onReload: (String) -> Unit,
) {
    val today = remember { LocalDate.now().toString() }
    val currentMonthLd = remember(welfare) {
        welfare?.current_month?.takeIf { it > 0 }?.let { m ->
            LocalDate.now().withMonth(m).withDayOfMonth(1)
        } ?: run {
            welfare?.sign_list?.firstOrNull { it.status.isNotEmpty() }?.date?.let {
                runCatching { LocalDate.parse(it).withDayOfMonth(1) }.getOrNull()
            }
        } ?: LocalDate.now().withDayOfMonth(1)
    }
    val monthText = "${currentMonthLd.year}年${currentMonthLd.monthValue}月"
    val currentMonthStr = String.format("%04d-%02d-01", currentMonthLd.year, currentMonthLd.monthValue)
    val prevMonthStr = run {
        val p = currentMonthLd.minusMonths(1)
        String.format("%04d-%02d-01", p.year, p.monthValue)
    }
    val nextMonthStr = run {
        val n = currentMonthLd.plusMonths(1)
        String.format("%04d-%02d-01", n.year, n.monthValue)
    }
    val consecutive = welfare?.consecutive_sign?.takeIf { it > 0 } ?: welfare?.user_data?.consecutiveDays ?: 0
    val point = welfare?.user_data?.point ?: 0

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onReload(prevMonthStr) }, enabled = !loading) { Text("上一月") }
            Text(
                monthText,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onReload(nextMonthStr) }, enabled = !loading) { Text("下一月") }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                "连续 $consecutive 天",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                "积分 $point",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                Text(
                    day,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        when {
            loading -> Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            error != null -> Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "签到数据加载失败：$error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onReload(currentMonthStr) }) { Text("重试") }
            }
            welfare == null || welfare.sign_list.isEmpty() -> Text(
                "暂无签到数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            else -> {
                welfare.sign_list.chunked(7).forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            Box(modifier = Modifier.weight(1f)) { SignDayCell(day = day, isToday = day.date == today) }
                        }
                        repeat(7 - week.size) { Box(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(4.dp))
                Text("已签到", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.width(4.dp))
                Text("未签到", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(12.dp)
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(4.dp))
                Text("今日", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    HorizontalDivider()
}

/** 签到日历中的单个日期格子。 */
@Composable
private fun SignDayCell(day: SignDay, isToday: Boolean) {
    Box(
        modifier = Modifier
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        SignCalendarDayCell(
            dayNumber = day.dayNum,
            isSigned = day.isSigned,
            isMissed = day.isMissed,
            isToday = isToday,
            isFuture = day.isFuture,
            isPlaceholder = day.isPlaceholder,
        )
    }
}
