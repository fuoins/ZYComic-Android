package com.zycomic.app.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

private enum class JmAccountMode { LOGIN, REGISTER, ACTIVATE, FORGOT }

/** All form values and jobs are scoped to JM and discarded when the platform changes. */
@Composable
internal fun JmAccountPanel(onLoggedIn: () -> Unit, onBusyChanged: (Boolean) -> Unit) {
    var mode by remember { mutableStateOf(JmAccountMode.LOGIN) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun select(next: JmAccountMode) {
        if (busy) return
        mode = next
        password = ""
        confirmation = ""
        code = ""
        message = null
    }
    fun submit() {
        if (busy) return
        busy = true
        onBusyChanged(true)
        message = null
        failed = false
        scope.launch {
            try {
                when (mode) {
                    JmAccountMode.LOGIN -> {
                        JmAccountLogin.login(username, password)
                        password = ""
                        onLoggedIn()
                    }
                    JmAccountMode.REGISTER -> {
                        message = JmAccountApi.register(username, email, password, confirmation)
                        password = ""
                        confirmation = ""
                        mode = JmAccountMode.ACTIVATE
                    }
                    JmAccountMode.ACTIVATE -> {
                        message = JmAccountApi.activate(username, code)
                        password = ""
                        code = ""
                        mode = JmAccountMode.LOGIN
                    }
                    JmAccountMode.FORGOT -> {
                        message = JmAccountApi.forgotPassword(email)
                        password = ""
                        mode = JmAccountMode.LOGIN
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed = true
                message = error.message
            } finally {
                busy = false
                onBusyChanged(false)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(
                when (mode) {
                    JmAccountMode.LOGIN -> KMR.strings.jm_login
                    JmAccountMode.REGISTER -> KMR.strings.jm_register
                    JmAccountMode.ACTIVATE -> KMR.strings.jm_activate
                    JmAccountMode.FORGOT -> KMR.strings.jm_forgot_password
                },
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        if (mode != JmAccountMode.FORGOT) {
            OutlinedTextField(value = username, onValueChange = { username = it }, enabled = !busy, label = { Text(stringResource(KMR.strings.jm_username_login_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (mode == JmAccountMode.REGISTER || mode == JmAccountMode.FORGOT) {
            OutlinedTextField(value = email, onValueChange = { email = it }, enabled = !busy, label = { Text(stringResource(KMR.strings.jm_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (mode == JmAccountMode.LOGIN || mode == JmAccountMode.REGISTER) {
            OutlinedTextField(value = password, onValueChange = { password = it }, enabled = !busy, label = { Text(stringResource(KMR.strings.jm_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
        when (mode) {
            JmAccountMode.LOGIN -> Text(stringResource(KMR.strings.jm_password_login_notice), style = MaterialTheme.typography.bodySmall)
            JmAccountMode.REGISTER -> {
                OutlinedTextField(value = confirmation, onValueChange = { confirmation = it }, enabled = !busy, label = { Text(stringResource(KMR.strings.jm_confirm_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text(stringResource(KMR.strings.jm_registration_notice), style = MaterialTheme.typography.bodySmall)
            }
            JmAccountMode.ACTIVATE -> {
                OutlinedTextField(value = code, onValueChange = { code = it }, enabled = !busy, label = { Text(stringResource(KMR.strings.jm_activation_code)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(KMR.strings.jm_resend_notice), color = Color.Red, style = MaterialTheme.typography.bodySmall)
            }
            JmAccountMode.FORGOT -> Text(stringResource(KMR.strings.jm_forgot_notice), style = MaterialTheme.typography.bodySmall)
        }
        message?.let { Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (busy) KMR.strings.jm_account_working else KMR.strings.jm_account_submit))
        }
        if (mode == JmAccountMode.LOGIN) {
            Row {
                TextButton(onClick = { select(JmAccountMode.REGISTER) }, enabled = !busy) { Text(stringResource(KMR.strings.jm_register)) }
                TextButton(onClick = { select(JmAccountMode.ACTIVATE) }, enabled = !busy) { Text(stringResource(KMR.strings.jm_activate)) }
                TextButton(onClick = { select(JmAccountMode.FORGOT) }, enabled = !busy) { Text(stringResource(KMR.strings.jm_forgot_password)) }
            }
        } else {
            TextButton(onClick = { select(JmAccountMode.LOGIN) }, enabled = !busy) { Text(stringResource(KMR.strings.jm_back_to_login)) }
        }
        if (mode == JmAccountMode.LOGIN) {
            Text(stringResource(KMR.strings.jm_registration_warning), color = Color.Red, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun JmPasswordChangeDialog(onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(KMR.strings.jm_change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(KMR.strings.jm_password_profile_notice))
                if (!done) {
                    OutlinedTextField(value = password, onValueChange = { password = it }, enabled = !busy, label = { Text(stringResource(KMR.strings.jm_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(value = confirmation, onValueChange = { confirmation = it }, enabled = !busy, label = { Text(stringResource(KMR.strings.jm_confirm_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
                message?.let { Text(it, color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (done) {
                    onDismiss()
                } else {
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            message = JmAccountApi.changePassword(password, confirmation)
                            password = ""
                            confirmation = ""
                            done = true
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            message = error.message
                        } finally {
                            busy = false
                        }
                    }
                }
            }) {
                Text(
                    stringResource(
                        if (done) {
                            KMR.strings.jm_account_done
                        } else if (busy) {
                            KMR.strings.jm_account_working
                        } else {
                            KMR.strings.jm_account_submit
                        },
                    ),
                )
            }
        },
        dismissButton = { if (!done) TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(KMR.strings.jm_account_cancel)) } },
    )
}
