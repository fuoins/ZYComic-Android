package com.zycomic.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.imageLoader
import com.zycomic.app.platform.ContentPlatform

/**
 * 全应用随机封面背景：封面放大模糊 + 只压顶部（状态栏/搜索栏区）与底部（底栏区）两段渐隐，
 * 中部大面积完全放开。按封面所属平台选择图片加载器（漫蛙走全局栈、哔咔/JM 走各自专用栈）。
 * 无封面时整个组件为空，页面露出全局背景。
 */
@Composable
fun AppCoverBackdrop(entry: BackdropEntry?, modifier: Modifier = Modifier) {
    if (entry == null) return
    val context = LocalContext.current
    val loader = remember(entry.platform) {
        when (entry.platform) {
            ContentPlatform.PICACG -> PicacgImageLoader.get(context)
            ContentPlatform.JM -> JmCoverImageLoader.get(context)
            else -> context.imageLoader
        }
    }
    Box(modifier) {
        AsyncImage(
            model = entry.url,
            contentDescription = null,
            imageLoader = loader,
            modifier = Modifier.matchParentSize().blur(8.dp).alpha(0.55f),
            contentScale = ContentScale.Crop,
        )
        // 遮罩只做两件事：顶部保住状态栏/搜索栏文字、底部托住玻璃底栏，中间大面积放开让封面可见。
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                    0.10f to Color.Transparent,
                    0.85f to Color.Transparent,
                    1f to MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                ),
            ),
        )
    }
}
