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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
 * 兜底丰富渐变：与开屏页同一套语言（三段冷暖底色 + 六个柔和色团 + 中央柔光），
 * 静态无动画版本，深浅色自适应。
 */
@Composable
private fun RichFallbackBackdrop(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    androidx.compose.foundation.Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.linearGradient(
                0f to if (dark) Color(0xFF17131F) else Color(0xFFFFFBF4),
                0.42f to if (dark) Color(0xFF1B1A33) else Color(0xFFF1F7FF),
                1f to if (dark) Color(0xFF0B0A11) else Color(0xFFFFF3E8),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            ),
        )
        fun orb(cx: Float, cy: Float, radius: Float, color: Color, alpha: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.42f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
        val boost = if (dark) 0.72f else 1.02f
        orb(-0.10f * size.width, -0.08f * size.height, 0.62f * size.width, Color(0xFF2FE3AE), 0.84f * boost)
        orb(1.02f * size.width, 0.22f * size.height, 0.52f * size.width, Color(0xFFA78BFA), 0.78f * boost)
        orb(0.72f * size.width, 1.02f * size.height, 0.60f * size.width, Color(0xFFFFA45C), 0.82f * boost)
        orb(0.94f * size.width, 0.62f * size.height, 0.26f * size.width, Color(0xFF5FB4FF), 0.52f * boost)
        orb(0.16f * size.width, 0.20f * size.height, 0.24f * size.width, Color(0xFFFF8FB1), 0.40f * boost)
        orb(0.06f * size.width, 0.56f * size.height, 0.22f * size.width, Color(0xFF6FF0DF), 0.56f * boost)
        // 中央柔光只留一点点，保证文字干净又不把颜色洗白
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (dark) 0.05f else 0.20f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.46f, size.height * 0.40f),
                radius = size.width * 0.72f,
            ),
        )
    }
}
@Composable
fun AppCoverBackdrop(entry: BackdropEntry?, modifier: Modifier = Modifier) {
    if (entry == null) {
        // 兜底：与开屏页同语言的丰富渐变（三段冷暖底色 + 六个柔和色团），深浅色自适应。
        RichFallbackBackdrop(modifier)
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
                modifier = Modifier.matchParentSize().blur(backdropBlurDp.dp).alpha(0.85f),
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
