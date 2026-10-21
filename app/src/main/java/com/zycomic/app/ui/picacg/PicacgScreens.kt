package com.zycomic.app.ui.picacg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zycomic.app.picacg.PicacgAuthStore
import com.zycomic.app.picacg.PicacgLoginException
import com.zycomic.app.picacg.PicacgRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

internal fun sessionIsCurrent(revision: Long): Boolean =
    PicacgAuthStore.loggedIn.value && revision == PicacgAuthStore.sessionRevision.value

@Composable
fun PicacgLoginScreen(
    onClose: () -> Unit,
    insetContent: Boolean = true,
    platformSwitch: @Composable (loading: Boolean) -> Unit = {},
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val loginFailed = stringResource(KMR.strings.picacg_login_failed)
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .then(if (insetContent) Modifier.statusBarsPadding().navigationBarsPadding().padding(24.dp) else Modifier)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onClose, enabled = !loading) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(KMR.strings.picacg_back), tint = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(KMR.strings.picacg_login_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                platformSwitch(loading)
            }
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(KMR.strings.zy_account_picacg_username)) }, singleLine = true, enabled = !loading, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), shape = MaterialTheme.shapes.small)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(KMR.strings.picacg_password)) }, singleLine = true, enabled = !loading, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = MaterialTheme.shapes.small)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            PicacgRepository.login(email.trim(), password)
                            password = ""
                            onClose()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = (failure as? PicacgLoginException)?.message ?: loginFailed
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) { Text(stringResource(if (loading) KMR.strings.picacg_loading else KMR.strings.zy_account_picacg_login_action)) }
        }
    }
}

@Composable
fun PicacgLoginDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { PicacgLoginScreen(onDismiss) }
}

@Composable
fun PicacgProfileContent(onRequireLogin: () -> Unit) {
    val loggedIn by PicacgAuthStore.loggedIn.collectAsState()
    val observedSession by PicacgAuthStore.sessionRevision.collectAsState()
    val session = observedSession
    var name by remember(session) { mutableStateOf("") }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var message by remember(session) { mutableStateOf<String?>(null) }
    var busy by remember(session) { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val punchSuccess = stringResource(KMR.strings.picacg_punch_in_success)
    LaunchedEffect(loggedIn, session, refresh) {
        if (!loggedIn) return@LaunchedEffect
        busy = true
        try {
            val response = PicacgRepository.profile()
            if (sessionIsCurrent(session)) {
                name = response.name
                error = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (sessionIsCurrent(session)) error = failure.message
        } finally {
            busy = false
        }
    }
    // The shared profile page owns scrolling; do not nest a scrolling container here.
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(KMR.strings.picacg_profile_title), style = MaterialTheme.typography.titleMedium)
        if (!loggedIn) {
            Text(stringResource(KMR.strings.picacg_session_required))
            Button(onClick = onRequireLogin) { Text(stringResource(KMR.strings.picacg_login_action)) }
        } else {
            Text(name)
            if (busy) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it) }
            TextButton(onClick = { refresh++ }, enabled = !busy) { Text(stringResource(KMR.strings.picacg_profile_refresh)) }
            Button(onClick = {
                busy = true
                error = null
                message = null
                scope.launch {
                    try {
                        PicacgRepository.punchIn()
                        if (sessionIsCurrent(session)) message = punchSuccess
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (sessionIsCurrent(session)) error = failure.message
                    } finally {
                        busy = false
                    }
                }
            }, enabled = !busy) { Text(stringResource(KMR.strings.picacg_punch_in)) }
            TextButton(onClick = { PicacgRepository.logout() }, enabled = !busy) { Text(stringResource(KMR.strings.picacg_logout)) }
        }
        Text(stringResource(KMR.strings.picacg_features_pending), style = MaterialTheme.typography.bodySmall)
    }
}
