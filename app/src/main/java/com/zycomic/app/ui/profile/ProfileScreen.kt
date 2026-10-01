package com.zycomic.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zycomic.app.data.repository.UserRepository
import com.zycomic.app.ui.theme.BluePrimary
import com.zycomic.app.ui.theme.OffWhite
import com.zycomic.app.ui.theme.TextSecondary

@Composable
fun ProfileScreen(onRequireLogin: () -> Unit) {
    val user by UserRepository.userFlow.collectAsState()
    var showPointLogs by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Text("我的", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))

        // 用户信息
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(56.dp).clip(CircleShape).background(OffWhite),
                contentAlignment = Alignment.Center,
            ) {
                Text("${user?.nickname?.firstOrNull() ?: "?"}", color = BluePrimary)
            }
            Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                if (user == null) {
                    Button(onClick = onRequireLogin) { Text("登录") }
                } else {
                    Text(user!!.nickname.ifBlank { "未设置昵称" }, style = MaterialTheme.typography.titleMedium)
                    Text("Lv${user!!.level}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }

        // 数据
        if (user != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                StatItem("收藏", "${user!!.favoriteCount}")
                StatItem("等级", "Lv${user!!.level}")
                StatItem("积分", pointLimitText(user!!.level, user!!.point))
            }

            // 功能入口
            SettingEntry("积分明细") { showPointLogs = true }
            SettingEntry("修改密码") { }
            SettingEntry("我的评论") { }

            // 退出登录
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Button(onClick = { UserRepository.logout() }) {
                    Text("退出登录")
                }
            }
        }
    }

    if (showPointLogs) {
        PointLogsOverlay(onClose = { showPointLogs = false })
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = BluePrimary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

@Composable
private fun SettingEntry(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(">", color = TextSecondary)
    }
}

@Composable
private fun PointLogsOverlay(onClose: () -> Unit) {
    val vm = remember { PointLogsViewModel() }
    val logs by vm.logs.collectAsState()
    val loading by vm.loading.collectAsState()

    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                Text("积分明细", style = MaterialTheme.typography.titleMedium)
            }
            // 等级说明
            Column(modifier = Modifier.padding(16.dp)) {
                Text("等级划分：", style = MaterialTheme.typography.titleSmall)
                Text("Lv1: 0-500", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text("Lv2: 501-3000", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text("Lv3: 3001-6000", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text("Lv4: 6001-10000", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text("Lv5: 10001+", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text(
                    "等级和积分仅开发者对接口的设置,属于自用功能,无视即可",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logs.size) { i ->
                        val l = logs[i]
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(l.remark, modifier = Modifier.weight(1f))
                            Text(if (l.point >= 0) "+${l.point}" else "${l.point}", color = BluePrimary)
                        }
                    }
                }
            }
        }
    }
}
