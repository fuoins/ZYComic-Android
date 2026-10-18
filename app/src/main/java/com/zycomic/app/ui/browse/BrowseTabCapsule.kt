package com.zycomic.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
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
) {
    val platformAccent = rememberContentPlatformAccent(platform)
    val accent = if (isYuzongRank) MaterialTheme.colorScheme.primary else platformAccent
    val background = if (selected) accent else accent.copy(alpha = if (isJmHome) 0.17f else 0.12f)
    val foreground = when {
        !selected -> accent
        isYuzongRank -> MaterialTheme.colorScheme.onPrimary
        accent.luminance() > 0.55f -> Color.Black
        else -> Color.White
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    ) {
        Text(
            text = name,
            fontSize = fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = foreground,
            fontWeight = if (selected || isJmHome) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
