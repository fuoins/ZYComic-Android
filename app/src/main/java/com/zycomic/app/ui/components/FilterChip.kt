package com.zycomic.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 胶囊筛选组件。
 *
 * 严格约束：用 Box + clickable + background 实现，绝不使用 Surface
 * （Surface 有 48dp 最小高度会撑高）。
 * - 内距左右 8dp、上下 4dp
 * - 选中：主色背景 + 白字；未选中：灰背景 + 黑字
 * - 圆角 50% 胶囊
 */
@Composable
fun FilterChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    val accent by AppearanceColors.uiAccent.collectAsState()
    val contentColor = if (selected) accent ?: MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .glassSurface(
                shape = shape,
                radius = 999.dp,
                part = if (selected) GlassPart.SUBTAB_SELECTED else GlassPart.SUBTAB_UNSELECTED,
                stroke = false,
            )
            .clickable(interaction, indication = null) { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = contentColor,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * 带边框的胶囊（用于"更多"等需要描边区分的场景，同样不用 Surface）。
 */
@Composable
fun OutlinedFilterChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    val accent by AppearanceColors.uiAccent.collectAsState()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .glassSurface(
                shape = shape,
                radius = 999.dp,
                part = if (selected) GlassPart.SUBTAB_SELECTED else GlassPart.SUBTAB_UNSELECTED,
                stroke = true,
            )
            .clickable(interaction, indication = null) { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) accent ?: MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
