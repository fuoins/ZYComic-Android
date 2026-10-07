package com.zycomic.app.ui.login

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginOverlay(onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
            ) {
                LoginScreen(onClose = onClose)
            }
        }
    }
}

@Composable
fun LoginScreen(vm: LoginViewModel = remember { LoginViewModel() }, onClose: () -> Unit = {}) {
    val mode by vm.mode.collectAsState()
    val username by vm.username.collectAsState()
    val password by vm.password.collectAsState()
    val confirmPassword by vm.confirmPassword.collectAsState()
    val captchaInput by vm.captchaInput.collectAsState()
    val email by vm.email.collectAsState()
    val authCode by vm.authCode.collectAsState()
    val captchaImage by vm.captchaImage.collectAsState()
    val countdown by vm.authCountdown.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val success by vm.success.collectAsState()

    LaunchedEffect(success) { if (success) onClose() }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.width(8.dp))
            Text(when (mode) { 0 -> "账号登录"; 1 -> "邮箱登录"; else -> "注册" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(16.dp))
        val tabs = listOf("账号登录", "邮箱登录", "注册")
        ScrollableTabRow(
            selectedTabIndex = mode,
            indicator = { TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(it[mode]), color = MaterialTheme.colorScheme.primary) },
            containerColor = MaterialTheme.colorScheme.surface,
            edgePadding = 0.dp,
        ) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = mode == i, onClick = { vm.mode.value = i }, text = {
                    Text(t, fontWeight = if (mode == i) FontWeight.Bold else FontWeight.Normal, color = if (mode == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                })
            }
        }
        Spacer(Modifier.height(16.dp))

        when (mode) {
            0 -> Column {
                OutlinedTextField(value = username, onValueChange = { vm.username.value = it }, label = { Text("账号") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = password, onValueChange = { vm.password.value = it }, label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    captchaImage?.let { bytes ->
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.let { bmp ->
                            Image(bitmap = bmp, contentDescription = "验证码", modifier = Modifier.width(100.dp).height(40.dp).clickable { vm.refreshCaptcha() })
                        }
                    } ?: Box(
                        modifier = Modifier.width(100.dp).height(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).clickable { vm.refreshCaptcha() },
                        contentAlignment = Alignment.Center,
                    ) { Text("点击刷新", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    OutlinedTextField(value = captchaInput, onValueChange = { vm.captchaInput.value = it }, label = { Text("验证码") }, singleLine = true, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.small)
                }
            }
            1 -> Column {
                OutlinedTextField(value = email, onValueChange = { vm.email.value = it }, label = { Text("邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = authCode, onValueChange = { vm.authCode.value = it }, label = { Text("邮箱验证码") }, singleLine = true, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.small)
                    OutlinedButton(onClick = { vm.sendAuthCode() }, enabled = countdown == 0) { Text(if (countdown > 0) "${countdown}s" else "获取验证码") }
                }
                Text("忘记密码或账号，邮箱登录后在[我的]页点击忘记密码", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            else -> Column {
                OutlinedTextField(value = username, onValueChange = { vm.username.value = it }, label = { Text("账号") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                Text("账号不能用中文，仅限英文与数字，长度6-32个字", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = password, onValueChange = { vm.password.value = it }, label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = confirmPassword, onValueChange = { vm.confirmPassword.value = it }, label = { Text("再次输入密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = email, onValueChange = { vm.email.value = it }, label = { Text("邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                Text("支持的邮箱类型: Gmail, QQ邮箱，163邮箱，新浪邮箱，Outlook, Yahoo邮箱", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = authCode, onValueChange = { vm.authCode.value = it }, label = { Text("邮箱验证码") }, singleLine = true, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.small)
                    OutlinedButton(onClick = { vm.sendAuthCode() }, enabled = countdown == 0) { Text(if (countdown > 0) "${countdown}s" else "获取验证码") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
        Button(onClick = { when (mode) { 0 -> vm.login(); 1 -> vm.emailLogin(); else -> vm.register() } }, enabled = !loading, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
            if (loading) CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), color = MaterialTheme.colorScheme.onPrimary)
            else Text(when (mode) { 0 -> "登录"; 1 -> "邮箱登录"; else -> "注册" })
        }
        if (mode != 2) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { vm.mode.value = 2 }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text("没有账号？注册") }
        }
        if (mode == 0) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.mode.value = 1 }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text("忘记密码") }
        }
    }
}
