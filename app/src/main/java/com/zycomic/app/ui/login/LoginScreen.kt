package com.zycomic.app.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(onClose: () -> Unit) {
    val vm = remember { LoginViewModel() }
    val username by vm.username.collectAsState()
    val password by vm.password.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val success by vm.success.collectAsState()

    LaunchedEffect(success) { if (success) onClose() }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("登录", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = username,
            onValueChange = { vm.username.value = it },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { vm.password.value = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        Button(
            onClick = { vm.login() },
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            enabled = !loading,
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            else Text("登录")
        }
        TextButton(onClick = { vm.register() }, enabled = !loading) {
            Text("没有账号？注册", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 登录覆盖层（full-screen Dialog）。登录成功后关闭，userFlow 自动更新。 */
@Composable
fun LoginOverlay(onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        LoginScreen(onClose = onClose)
    }
}
