package com.zycomic.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val NETWORK_MAINTENANCE_TEXT =
    "本软件对所有线路和图源进行网络层优化,使得无需vpn可连接90%+," +
        "原始源18:00以后会卡顿,所以若卡顿非软件原因,阅读器的加载和失败的策略和算法全部重新设计."

@Composable
fun NetworkMaintenanceNotice(modifier: Modifier = Modifier) {
    val textColor = MaterialTheme.colorScheme.onErrorContainer

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = NETWORK_MAINTENANCE_TEXT,
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
            )
            Text(
                text = "本软件仅供学习交流与个人技术研究使用。",
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
            )
            Text(
                text = "使用协议",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = textColor,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "1.请务必在获取后24小时内完成学习,并自行删除本软件；",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                )
                Text(
                    "2.严禁用于任何商业用途、盈利场景或非法用途:",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                )
                Text(
                    "3.请尊重目标应用的版权与权益,禁止传播、二次分发或售卖；",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                )
                Text(
                    "4.使用本软件产生的任何后果，由使用者自行承担:",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                )
            }
        }
    }
}
