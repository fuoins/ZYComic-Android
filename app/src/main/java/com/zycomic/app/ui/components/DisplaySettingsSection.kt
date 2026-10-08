package com.zycomic.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 共享的"显示"设置区：显示模式（紧凑/舒适/仅封面）+ 每行数量 Slider。
 *
 * 分类页（BrowseScreen 的"显示"页签）与搜索页（底部弹窗）共用同一份实现，
 * 保证两处样式与行为完全一致。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisplaySettingsSection(
    displayMode: Int,
    gridColumns: Int,
    orientation: Int,
    onSetDisplayMode: (Int) -> Unit,
    onSetGridColumns: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        FilterSection("显示模式") {
            listOf(0 to "紧凑网格", 1 to "舒适网格", 2 to "仅封面网格").forEach { (v, label) ->
                FilterChip(
                    text = label,
                    selected = displayMode == v,
                    onClick = { onSetDisplayMode(v) },
                )
            }
        }

        FilterSection("每行数量") {
            Text(
                if (gridColumns == 0) "自动" else gridColumns.toString(),
                modifier = Modifier.padding(end = 8.dp),
            )
            Slider(
                value = gridColumns.toFloat(),
                onValueChange = { onSetGridColumns(it.toInt()) },
                valueRange = 0f..10f,
                steps = 9,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
