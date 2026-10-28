package com.zycomic.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zycomic.app.ui.settings.ZYSettingsAppearanceScreen

/**
 * 外观预览浮窗：不离开主界面就能调完整外观设置（与外观页同一份内容：颜色 / 背景与玻璃 / 背景图 / 配置备份）。
 * 只保留「最小化」，最小化后回到右下角的「调整」悬浮按钮。
 */
@Composable
fun AppearanceQuickPanel(
    modifier: Modifier = Modifier,
    onMinimize: () -> Unit,
) {
    GlassCard(
        modifier = modifier.width(330.dp).height(452.dp),
        radius = 20.dp,
        part = GlassPart.CARD,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 2.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(17.dp))
                Text(
                    "外观调整",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                IconButton(onClick = onMinimize) {
                    Icon(Icons.Outlined.Remove, contentDescription = "最小化")
                }
            }
            HorizontalDivider()
            // 完整外观设置：4 个标签页 + 每个标签下的全部设置项。
            ZYSettingsAppearanceScreen.AppearanceSettingsBody(modifier = Modifier.weight(1f))
        }
    }
}
