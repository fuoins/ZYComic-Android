package com.zycomic.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.rememberContentPlatformAccent
import com.zycomic.app.ui.components.glassSurface

@Composable
fun BrowseTabCapsule(
    name: String,
    platform: ContentPlatform,
    isYuzongRank: Boolean = false,
    isJmHome: Boolean = false,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 12.dp,
    verticalPadding: Dp = 7.dp,
    fontSize: TextUnit = 12.sp,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val platformAccent = rememberContentPlatformAccent(platform)
    // 宇总排行用品牌蓝（开屏页品牌蓝，深浅色自适应），不跟随外观主题色；其余用各自平台色。
    val accent = if (isYuzongRank) {
        if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF8FB7FF) else Color(0xFF2F6FD0)
    } else {
        platformAccent
    }
    // 玻璃语言与底栏一致：选中 = 更亮的白色玻璃胶囊，未选中 = 淡玻璃（外观页可单独调，调到 0 即无底）。
    val foreground = accent

    Row(
        modifier = modifier
            .clip(CircleShape)
            .glassSurface(
                shape = RoundedCornerShape(50),
                radius = 999.dp,
                part = if (selected) {
                    com.zycomic.app.ui.components.GlassPart.TAB_SELECTED
                } else {
                    com.zycomic.app.ui.components.GlassPart.TAB_UNSELECTED
                },
                stroke = false,
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = name,
            fontSize = fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = foreground,
            fontWeight = if (selected || isJmHome) FontWeight.SemiBold else FontWeight.Medium,
        )
        trailingContent?.invoke()
    }
}

/** A compact chip without Material's invisible 48dp row gap. */
@Composable
fun CompactBrowseFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp,
    ) {
        androidx.compose.material3.FilterChip(
            selected = selected,
            onClick = onClick,
            label = label,
            modifier = Modifier.height(32.dp),
        )
    }
}
