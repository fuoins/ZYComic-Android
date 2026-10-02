package com.zycomic.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zycomic.app.ui.theme.BluePrimary
import com.zycomic.app.ui.theme.GrayChip
import com.zycomic.app.ui.theme.GrayChipBorder
import com.zycomic.app.ui.theme.TextPrimary

/**
 * 胶囊筛选组件。
 *
 * 严格约束：用 Box + clickable + background + border 实现，绝不使用 Surface
 * （Surface 有 48dp 最小高度会撑高）。
 * - 内距左右 8dp、上下 4dp
 * - 选中：主色背景 + 白字；未选中：灰背景 + 黑字
 * - 圆角 50% 胶囊
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(percent = 50)
    val bg = if (selected) BluePrimary else GrayChip
    val contentColor = if (selected) Color.White else TextPrimary
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = contentColor,
            fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.SemiBold
            else androidx.compose.ui.text.font.FontWeight.Normal,
        )
    }
}

/**
 * 带边框的胶囊（用于“更多”等需要描边区分的场景，同样不用 Surface）。
 */
@Composable
fun OutlinedFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) BluePrimary else Color.White)
            .clickable(interaction, indication = null) { onClick() }
            .then(
                if (!selected) Modifier.padding(1.dp) else Modifier
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) Color.White else TextPrimary,
        )
    }
}
