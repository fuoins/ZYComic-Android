package com.zycomic.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.imageLoader
import androidx.compose.ui.platform.LocalContext

/**
 * 全应用随机封面背景：与漫画详情页背景同款——封面放大模糊 + 上深下浅的渐变遮罩，
 * 再压一层主题底色保证文字可读。无封面时整个组件为空，页面就是普通主题底色。
 */
@Composable
fun AppCoverBackdrop(url: String?, modifier: Modifier = Modifier) {
    if (url == null) return
    val imageLoader = LocalContext.current.imageLoader
    Box(modifier) {
        AsyncImage(
            model = url,
            contentDescription = null,
            imageLoader = imageLoader,
            modifier = Modifier.matchParentSize().blur(14.dp).alpha(0.22f),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.background,
                    0.42f to MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                    1f to MaterialTheme.colorScheme.background,
                ),
            ),
        )
        // 底部导航区域再压一层，保证液态玻璃底栏上的图标文字清晰
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.78f to Color.Transparent,
                    1f to MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                ),
            ),
        )
    }
}
