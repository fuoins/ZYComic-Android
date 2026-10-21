package com.zycomic.app.ui.picacg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zycomic.app.picacg.AndroidPicacgAutoPunchBooleanStore
import com.zycomic.app.picacg.PicacgAuthStore
import com.zycomic.app.picacg.PicacgAutoPunchCoordinator
import com.zycomic.app.picacg.PicacgProfile
import com.zycomic.app.picacg.PicacgRepository
import com.zycomic.app.picacg.StoredPicacgAutoPunchPreference
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun PicacgAccountProfileContent(
    onRequireLogin: () -> Unit,
    refreshRequest: Int = 0,
    onBusyChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val loggedIn by PicacgAuthStore.loggedIn.collectAsState()
    val observedSession by PicacgAuthStore.sessionRevision.collectAsState()
    val session = observedSession
    var profile by remember(session) { mutableStateOf<PicacgProfile?>(null) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var message by remember(session) { mutableStateOf<String?>(null) }
    var busy by remember(session) { mutableStateOf(false) }
    var showBlockKeywordScreen by remember { mutableStateOf(false) }
    val autoPunchPreference = remember(context) {
        StoredPicacgAutoPunchPreference(AndroidPicacgAutoPunchBooleanStore(context))
    }
    var autoPunchEnabled by remember(context) { mutableStateOf(autoPunchPreference.enabled) }
    val scope = rememberCoroutineScope()
    val autoPunchCoordinator = remember(autoPunchPreference) {
        PicacgAutoPunchCoordinator(
            preference = autoPunchPreference,
            isLoggedIn = { PicacgAuthStore.loggedIn.value },
            currentSessionRevision = { PicacgAuthStore.sessionRevision.value },
            loadProfile = { expectedRevision -> PicacgRepository.profile(expectedRevision) },
            punchInAndRefresh = { expectedRevision -> PicacgRepository.punchIn(expectedRevision) },
        )
    }
    val punchSuccess = stringResource(KMR.strings.picacg_punch_in_success)
    val punchUnconfirmed = stringResource(KMR.strings.picacg_punch_in_unconfirmed)
    val punchUnknown = stringResource(KMR.strings.picacg_profile_check_in_unknown)
    var appliedRefreshRequest by remember(session) { mutableStateOf(refreshRequest) }

    LaunchedEffect(busy) { onBusyChanged(busy) }

    LaunchedEffect(loggedIn, session) {
        if (!loggedIn) return@LaunchedEffect
        busy = true
        try {
            val loaded = autoPunchCoordinator.loadProfileForPage(session)
            if (loaded != null && session == PicacgAuthStore.sessionRevision.value && PicacgAuthStore.loggedIn.value) {
                profile = loaded
                error = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (session == PicacgAuthStore.sessionRevision.value && PicacgAuthStore.loggedIn.value) error = failure.message
        } finally {
            busy = false
        }
    }

    LaunchedEffect(refreshRequest, loggedIn, session) {
        if (refreshRequest == appliedRefreshRequest || !loggedIn) return@LaunchedEffect
        appliedRefreshRequest = refreshRequest
        busy = true
        try {
            val refreshed = PicacgRepository.profile(session)
            if (session == PicacgAuthStore.sessionRevision.value && PicacgAuthStore.loggedIn.value) {
                profile = refreshed
                error = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (session == PicacgAuthStore.sessionRevision.value && PicacgAuthStore.loggedIn.value) error = failure.message
        } finally {
            busy = false
        }
    }

    if (showBlockKeywordScreen) {
        Dialog(
            onDismissRequest = { showBlockKeywordScreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                PicacgBlockKeywordScreen(onBack = { showBlockKeywordScreen = false })
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!loggedIn) {
            Text(stringResource(KMR.strings.picacg_session_required))
            TextPreferenceWidget(
                title = stringResource(KMR.strings.picacg_login_action),
                icon = Icons.Outlined.Info,
                onPreferenceClick = onRequireLogin,
            )
        } else {
            val user = profile
            if (user == null) {
                TextPreferenceWidget(
                    title = stringResource(KMR.strings.picacg_profile_email, "资料加载中…"),
                    subtitle = "账户资料正在加载",
                    icon = Icons.Outlined.Info,
                    onPreferenceClick = {},
                )
            } else {
                TextPreferenceWidget(
                    title = stringResource(KMR.strings.picacg_profile_email, user.email.ifBlank { user.name }),
                    subtitle = stringResource(KMR.strings.picacg_profile_level_exp, user.level, user.exp),
                    icon = Icons.Outlined.Info,
                    onPreferenceClick = {},
                )
            }
            TextPreferenceWidget(
                title = stringResource(
                    KMR.strings.picacg_profile_punched,
                    when (user?.isPunched) {
                        true -> stringResource(KMR.strings.picacg_profile_punched_yes)
                        false -> stringResource(KMR.strings.picacg_profile_punched_no)
                        null -> punchUnknown
                    },
                ),
                icon = Icons.Outlined.EventAvailable,
                onPreferenceClick = {
                    val loadedUser = profile
                    if (loadedUser != null && loadedUser.isPunched != true && !busy) {
                        busy = true
                        error = null
                        message = null
                        scope.launch {
                            try {
                                val refreshed = PicacgRepository.punchIn(session)
                                if (session == PicacgAuthStore.sessionRevision.value && PicacgAuthStore.loggedIn.value) {
                                    profile = refreshed
                                    message = if (refreshed.isPunched == true) punchSuccess else punchUnconfirmed
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                if (session == PicacgAuthStore.sessionRevision.value && PicacgAuthStore.loggedIn.value) error = failure.message
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
            )
            SwitchPreferenceWidget(
                title = stringResource(KMR.strings.picacg_auto_punch_in),
                subtitle = stringResource(KMR.strings.picacg_auto_punch_in_summary),
                icon = Icons.Outlined.EventRepeat,
                checked = autoPunchEnabled,
                onCheckedChanged = { enabled ->
                    autoPunchPreference.enabled = enabled
                    autoPunchEnabled = enabled
                },
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it) }
            TextPreferenceWidget(
                title = stringResource(KMR.strings.picacg_logout),
                icon = Icons.Outlined.Logout,
                onPreferenceClick = { if (!busy) PicacgRepository.logout() },
            )
        }
        TextPreferenceWidget(
            title = stringResource(KMR.strings.picacg_block_keyword_entry),
            icon = Icons.Outlined.Info,
            onPreferenceClick = { showBlockKeywordScreen = true },
        )
    }
}
