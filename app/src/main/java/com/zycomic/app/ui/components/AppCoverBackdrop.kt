package com.zycomic.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import com.zycomic.app.platform.ContentPlatform

/**
 * 全应用随机封面背景：封面放大模糊 + 只压顶部（状态栏/搜索栏区）与底部（底栏区）两段渐隐，
 * 中部大面积完全放开。按封面所属平台选择图片加载器（漫蛙走全局栈、哔咔/JM 走各自专用栈）。
 *
 * 防白屏策略：底色永远垫一层中性渐变（深浅色自适应）；切换背景时**旧封面保持显示**，
 * 新封面在后台预取（写进磁盘/内存缓存），就绪后才 450ms 淡入切换。
 */
@Composable
fun AppCoverBackdrop(entry: BackdropEntry?, modifier: Modifier = Modifier) {
    // 中性兜底渐变：跟随系统浅色/深色，不带主题色。封面未就绪时页面也绝不纯白。
    val fallbackBrush = Brush.verticalGradient(
        0f to MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        0.35f to MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
        1f to MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    )
    if (entry == null) {
        Box(modifier.background(fallbackBrush))
        return
    }

    val context = LocalContext.current
    val backdropBlurDp by GlassPrefs.backdropBlurDp.collectAsState()
    val backdropMask by GlassPrefs.backdropMask.collectAsState()
    // 当前正在展示的封面（双缓冲）：entry 变化后先继续显示旧封面，新封面就绪才切换。
    var shown by remember { mutableStateOf(entry) }
    val prefetchLoader = remember(entry.platform) {
        when (entry.platform) {
            ContentPlatform.PICACG -> PicacgImageLoader.get(context)
            ContentPlatform.JM -> JmCoverImageLoader.get(context)
            // 随机图源/自定义本地图：纯净 loader（仅 UA），不经过漫蛙图片栈的域改写与解密。
            else -> com.zycomic.app.platform.PlatformImageLoader.get(context)
        }
    }
    LaunchedEffect(entry, prefetchLoader) {
        if (entry != shown) {
            val metrics = context.resources.displayMetrics
            val request = ImageRequest.Builder(context)
                .data(entry.url)
                .size(Size(metrics.widthPixels, metrics.heightPixels))
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
            // 预取成功（图已在缓存）后才切换，旧封面全程兜底，杜绝白屏。
            if (prefetchLoader.execute(request) is SuccessResult) {
                shown = entry
            }
        }
    }

    Crossfade(targetState = shown, animationSpec = tween(450), label = "appBackdrop") { shownEntry ->
        val shownLoader = remember(shownEntry.platform) {
            when (shownEntry.platform) {
                ContentPlatform.PICACG -> PicacgImageLoader.get(context)
                ContentPlatform.JM -> JmCoverImageLoader.get(context)
                else -> com.zycomic.app.platform.PlatformImageLoader.get(context)
            }
        }
        Box(modifier) {
            AsyncImage(
                model = shownEntry.url,
                contentDescription = null,
                imageLoader = shownLoader,
                modifier = Modifier.matchParentSize().blur(backdropBlurDp.dp).alpha(0.65f),
                contentScale = ContentScale.Crop,
            )
            // 遮罩只做两件事：顶部保住状态栏/搜索栏文字、底部托住玻璃底栏，中间大面积放开让封面可见。
            // 强度由外观页「背景遮罩强度」调整。
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.background.copy(alpha = 0.45f * backdropMask),
                        0.08f to Color.Transparent,
                        0.88f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.background.copy(alpha = 0.45f * backdropMask),
                    ),
                ),
            )
        }
    }
}
