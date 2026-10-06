package com.zycomic.app.ui.login

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zycomic.app.ui.components.FilterChip

@Composable
fun LoginScreen(vm: LoginViewModel = LoginViewModel(), onClose: () -> Unit = {}) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("ZYComic", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("登录", mode == 0) { vm.mode.value = 0 }
            FilterChip("注册", mode == 1) { vm.mode.value = 1 }
        }
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(value = username, onValueChange = { vm.username.value = it }, label = { Text("账号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (mode == 1) Text("账号不能用中文，仅限英文与数字，长度6-32个字", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = password, onValueChange = { vm.password.value = it }, label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())

        if (mode == 0) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                captchaImage?.let {
                    Image(
                        bitmap = BitmapFactory.decodeByteArray(it, 0, it.size).asImageBitmap(),
                        contentDescription = "验证码",
                        modifier = Modifier.width(100.dp).height(40.dp).let { it.then(androidx.compose.foundation.Modifier.clickable(onClick = { vm.refreshCaptcha() })) },
                    )
                }
                OutlinedTextField(value = captchaInput, onValueChange = { vm.captchaInput.value = it }, label = { Text("验证码") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        } else {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = confirmPassword, onValueChange = { vm.confirmPassword.value = it }, label = { Text("再次输入密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = email, onValueChange = { vm.email.value = it }, label = { Text("邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("支持的邮箱类型: Gmail, QQ邮箱，163邮箱，新浪邮箱，Outlook, Yahoo邮箱", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = authCode, onValueChange = { vm.authCode.value = it }, label = { Text("邮箱验证码") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { vm.sendAuthCode() }, enabled = countdown == 0) {
                    Text(if (countdown > 0) "${countdown}s" else "获取验证码")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        error?.let { Text(it, color = Color(0xFFE53935), modifier = Modifier.padding(bottom = 8.dp)) }
        Button(
            onClick = { if (mode == 0) vm.login() else vm.register() },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), color = MaterialTheme.colorScheme.onPrimary)
            else Text(if (mode == 0) "登录" else "注册")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { vm.mode.value = if (mode == 0) 1 else 0 }, modifier = Modifier.fillMaxWidth()) {
            Text(if (mode == 0) "没有账号？注册" else "已有账号？去登录")
        }
    }
}
