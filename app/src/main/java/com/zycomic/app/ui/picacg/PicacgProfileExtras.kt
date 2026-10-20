package com.zycomic.app.ui.picacg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.zycomic.app.picacg.PicacgAuthStore
import com.zycomic.app.picacg.PicacgProfile
import com.zycomic.app.picacg.PicacgRepository
import com.zycomic.app.ui.components.PicacgImageLoader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun PicacgAccountProfileContent(onRequireLogin: () -> Unit) {
    val context = LocalContext.current
    val loggedIn by PicacgAuthStore.loggedIn.collectAsState()
    val observedSession by PicacgAuthStore.sessionRevision.collectAsState()
    val session = observedSession
    var profile by remember(session) { mutableStateOf<PicacgProfile?>(null) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var message by remember(session) { mutableStateOf<String?>(null) }
    var busy by remember(session) { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val punchSuccess = stringResource(KMR.strings.picacg_punch_in_success)
    val punchUnconfirmed = stringResource(KMR.strings.picacg_punch_in_unconfirmed)
    val punchDone = stringResource(KMR.strings.picacg_punch_in_done)
    val punchUnknown = stringResource(KMR.strings.picacg_profile_check_in_unknown)

    LaunchedEffect(loggedIn, session, refresh) {
        if (!loggedIn) return@LaunchedEffect
        busy = true
        try {
            val loaded = PicacgRepository.profile()
            if (session == PicacgAuthStore.sessionRevision.value && loggedIn) {
                profile = loaded
                error = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (session == PicacgAuthStore.sessionRevision.value && loggedIn) error = failure.message
        } finally {
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(KMR.strings.picacg_profile_title), style = MaterialTheme.typography.titleMedium)
        if (!loggedIn) {
            Text(stringResource(KMR.strings.picacg_session_required))
            Button(onClick = onRequireLogin) { Text(stringResource(KMR.strings.picacg_login_action)) }
        } else {
            profile?.let { user ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (user.avatarUrl.isNotBlank()) {
                        SubcomposeAsyncImage(
                            model = user.avatarUrl,
                            contentDescription = stringResource(KMR.strings.picacg_profile_avatar),
                            imageLoader = PicacgImageLoader.get(context),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(CircleShape),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(user.name.ifBlank { stringResource(KMR.strings.picacg_profile_default_name) }, style = MaterialTheme.typography.titleMedium)
                        user.title.takeIf(String::isNotBlank)?.let { Text(stringResource(KMR.strings.picacg_profile_member_title, it), style = MaterialTheme.typography.bodySmall) }
                        user.slogan.takeIf(String::isNotBlank)?.let { Text(stringResource(KMR.strings.picacg_profile_slogan, it), style = MaterialTheme.typography.bodySmall) }
                    }
                }
                listOfNotNull(
                    user.id.takeIf(String::isNotBlank)?.let { stringResource(KMR.strings.picacg_profile_user_id, it) },
                    user.email.takeIf(String::isNotBlank)?.let { stringResource(KMR.strings.picacg_profile_email, it) },
                    user.gender.takeIf(String::isNotBlank)?.let { stringResource(KMR.strings.picacg_profile_gender, it) },
                    user.birthday.takeIf(String::isNotBlank)?.let { stringResource(KMR.strings.picacg_profile_birthday, it) },
                    stringResource(KMR.strings.picacg_profile_level_exp, user.level, user.exp),
                    user.characters.takeIf { it.isNotEmpty() }?.joinToString()?.let { stringResource(KMR.strings.picacg_profile_characters, it) },
                    user.createdAt.takeIf(String::isNotBlank)?.let { stringResource(KMR.strings.picacg_profile_created_at, it) },
                    stringResource(
                        KMR.strings.picacg_profile_verified,
                        stringResource(if (user.verified) KMR.strings.picacg_profile_verified_yes else KMR.strings.picacg_profile_verified_no),
                    ),
                    stringResource(
                        KMR.strings.picacg_profile_punched,
                        when (user.isPunched) {
                            true -> stringResource(KMR.strings.picacg_profile_punched_yes)
                            false -> stringResource(KMR.strings.picacg_profile_punched_no)
                            null -> punchUnknown
                        },
                    ),
                ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (busy) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it) }
            TextButton(onClick = { refresh++ }, enabled = !busy) { Text(stringResource(KMR.strings.picacg_profile_refresh)) }
            Button(
                onClick = {
                    busy = true
                    error = null
                    message = null
                    scope.launch {
                        try {
                            val refreshed = PicacgRepository.punchIn()
                            if (session == PicacgAuthStore.sessionRevision.value && loggedIn) {
                                profile = refreshed
                                message = if (refreshed.isPunched == true) punchSuccess else punchUnconfirmed
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            if (session == PicacgAuthStore.sessionRevision.value && loggedIn) error = failure.message
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && profile?.isPunched == false,
            ) {
                Text(if (profile?.isPunched == true) punchDone else stringResource(KMR.strings.picacg_punch_in))
            }
            TextButton(onClick = { PicacgRepository.logout() }, enabled = !busy) { Text(stringResource(KMR.strings.picacg_logout)) }
        }
    }
}

@Composable
fun PicacgProfileExtras(onOpenSettings: () -> Unit) {
    TextPreferenceWidget(
        title = stringResource(KMR.strings.picacg_settings),
        icon = Icons.Outlined.Settings,
        onPreferenceClick = onOpenSettings,
    )
}
