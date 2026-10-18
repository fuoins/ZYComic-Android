package com.zycomic.app.ui.login

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zycomic.app.platform.ContentPlatform

/** Reuse the Manwa account/captcha/email flow without reading the profile's selected source. */
@Composable
internal fun ManwaDetailLoginDialog(onDismiss: () -> Unit) {
    val vm = remember { LoginViewModel() }
    val loading by vm.loading.collectAsState()
    Dialog(onDismissRequest = { if (!loading) onDismiss() }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp),
        ) {
            androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) {
                LoginScreen(vm = vm, onClose = onDismiss, fixedPlatform = ContentPlatform.MANWA)
            }
        }
    }
}
