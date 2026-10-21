package com.zycomic.app.ui.register

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.zycomic.app.picacg.PicacgRegistrationDraft
import com.zycomic.app.picacg.PicacgRegistrationException
import com.zycomic.app.picacg.PicacgRegistrationIssue
import com.zycomic.app.picacg.PicacgRegistrationPolicy
import com.zycomic.app.picacg.PicacgRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun PicacgRegisterScreen(onBack: () -> Unit) {
    var draft by remember { mutableStateOf(PicacgRegistrationDraft()) }
    var busy by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val registrationFailed = stringResource(KMR.strings.picacg_register_failed)
    val validationMessages = mapOf(
        PicacgRegistrationIssue.NAME_REQUIRED to stringResource(KMR.strings.picacg_register_name_required),
        PicacgRegistrationIssue.NAME_TOO_LONG to stringResource(KMR.strings.picacg_register_name_too_long),
        PicacgRegistrationIssue.USERNAME_INVALID to stringResource(KMR.strings.picacg_register_account_invalid),
        PicacgRegistrationIssue.PASSWORD_TOO_SHORT to stringResource(KMR.strings.picacg_register_password_short),
        PicacgRegistrationIssue.BIRTHDAY_INVALID to stringResource(KMR.strings.picacg_register_birthday_invalid),
        PicacgRegistrationIssue.UNDERAGE to stringResource(KMR.strings.picacg_register_underage),
        PicacgRegistrationIssue.SECURITY_ANSWERS_REQUIRED to stringResource(KMR.strings.picacg_register_security_required),
    )

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack, enabled = !busy) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(KMR.strings.picacg_back))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(KMR.strings.picacg_register_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text(stringResource(KMR.strings.picacg_register_account_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(
            onClick = {
                draft = PicacgRegistrationPolicy.generateDraft()
                error = null
                success = false
            },
            enabled = !busy && !success,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(KMR.strings.picacg_register_generate)) }

        RegistrationField(draft.name, { draft = draft.copy(name = it) }, stringResource(KMR.strings.picacg_register_name), busy || success)
        RegistrationField(
            draft.username,
            { draft = draft.copy(username = it) },
            stringResource(KMR.strings.picacg_register_account),
            busy || success,
            keyboardType = KeyboardType.Ascii,
        )
        RegistrationField(
            draft.password,
            { draft = draft.copy(password = it) },
            stringResource(KMR.strings.picacg_password),
            busy || success,
            keyboardType = KeyboardType.Password,
            password = true,
        )
        RegistrationField(draft.birthday, { draft = draft.copy(birthday = it) }, stringResource(KMR.strings.picacg_register_birthday), busy || success, keyboardType = KeyboardType.Ascii)

        RegistrationField(draft.question1, { draft = draft.copy(question1 = it) }, stringResource(KMR.strings.picacg_register_question_1), busy || success)
        RegistrationField(draft.answer1, { draft = draft.copy(answer1 = it) }, stringResource(KMR.strings.picacg_register_answer_1), busy || success)
        RegistrationField(draft.question2, { draft = draft.copy(question2 = it) }, stringResource(KMR.strings.picacg_register_question_2), busy || success)
        RegistrationField(draft.answer2, { draft = draft.copy(answer2 = it) }, stringResource(KMR.strings.picacg_register_answer_2), busy || success)
        RegistrationField(draft.question3, { draft = draft.copy(question3 = it) }, stringResource(KMR.strings.picacg_register_question_3), busy || success)
        RegistrationField(draft.answer3, { draft = draft.copy(answer3 = it) }, stringResource(KMR.strings.picacg_register_answer_3), busy || success)

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (success) Text(stringResource(KMR.strings.picacg_register_success), color = MaterialTheme.colorScheme.primary)
        Button(
            onClick = {
                val issue = PicacgRegistrationPolicy.validate(draft)
                if (issue != null) {
                    error = validationMessages.getValue(issue)
                    return@Button
                }
                busy = true
                error = null
                scope.launch {
                    try {
                        PicacgRepository.register(draft)
                        success = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = (failure as? PicacgRegistrationException)?.message ?: registrationFailed
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && !success,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(stringResource(KMR.strings.picacg_register_submit))
            }
        }
    }
}

@Composable
private fun RegistrationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    disabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = !disabled,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = MaterialTheme.shapes.small,
    )
}
