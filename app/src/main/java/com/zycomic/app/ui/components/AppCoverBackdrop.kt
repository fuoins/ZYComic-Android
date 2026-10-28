package com.zycomic.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import com.zycomic.app.platform.ContentPlatform

/**
 * 全应用背景：按封面所属平台选择图片加载器（漫蛙走全局栈、哔咔/JM 走各自专用栈），
 * 随机图源/自定义本地图走纯净 loader。模糊、遮罩、透明度均由外观页滑杆实时控制。
 *
 * 切换策略：**瞬时切换**——entry 变化立即开始 350ms 淡入动画，图片未就绪时底层
 * 丰富渐变兜底（绝不白屏）；同时后台预取新图写进缓存，让图片尽快显示。
 */
@Composable
fun AppCoverBackdrop(entry: BackdropEntry?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val backdropBlurDp by GlassPrefs.backdropBlurDp.collectAsState()
    val backdropAlpha by GlassPrefs.backdropAlpha.collectAsState()

    // 双缓冲：shown 是当前画面上的图；pending 是新图（先透明预解码，成功后再换）。
    // 首次组合时用全局记住的上一张，搜索页这种独立窗口因此不会从空白开始。
    var shown by remember { mutableStateOf(AppBackdropState.lastRendered ?: entry) }
    var pending by remember { mutableStateOf<BackdropEntry?>(null) }

    LaunchedEffect(entry) {
        when {
            entry == null -> {
                // 本页没有背景来源：退回内置默认图。
                shown = null
                pending = null
            }
            entry == shown -> pending = null
            else -> pending = entry
        }
    }
    LaunchedEffect(shown) {
        shown?.let { AppBackdropState.lastRendered = it }
    }

    // 后台预热（不阻塞切换）：把目标图下载进磁盘/内存缓存。
    LaunchedEffect(entry) {
        entry ?: return@LaunchedEffect
        val metrics = context.resources.displayMetrics
        val request = ImageRequest.Builder(context)
            .data(entry.url)
            .size(Size(metrics.widthPixels, metrics.heightPixels))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
        loaderFor(context, entry.platform).execute(request)
    }

    Box(modifier) {
        // 默认背景：内置图片垫底（用户自定义/随机源未就绪时也绝不白屏）。
        DefaultBackdropImage(
            modifier = Modifier.matchParentSize().blur(backdropBlurDp.dp).alpha(backdropAlpha),
            fallbackAlpha = 0.9f,
        )
        // 已显示的图：切换时只在这层之间淡入淡出，永不出现“图还没来”的空档。
        Crossfade(
            targetState = shown,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(350),
            label = "appBackdrop",
        ) { current ->
            if (current != null) {
                val loader = remember(current.platform) { loaderFor(context, current.platform) }
                AsyncImage(
                    model = current.url,
                    contentDescription = null,
                    imageLoader = loader,
                    modifier = Modifier.fillMaxSize().blur(backdropBlurDp.dp).alpha(backdropAlpha),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        // 预解码层：完全透明地加载下一张；成功后替换 shown，再走 350ms 淡入。
        pending?.let { next ->
            val loader = remember(next.platform) { loaderFor(context, next.platform) }
            AsyncImage(
                model = next.url,
                contentDescription = null,
                imageLoader = loader,
                modifier = Modifier.fillMaxSize().alpha(0f),
                contentScale = ContentScale.Crop,
                onSuccess = {
                    shown = next
                    AppBackdropState.lastRendered = next
                    pending = null
                },
                onError = { pending = null },
            )
        }
        // 顶部/底部轻压色，保证状态栏与底栏文字可读。
        if (shown != null) {
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.background.copy(alpha = 0.35f),
                        0.08f to Color.Transparent,
                        0.90f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.background.copy(alpha = 0.35f),
                    ),
                ),
            )
        }
    }
}

/** 内置默认背景图（assets/drawable-nodpi/backdrop_default.png），加载失败时退化渐变。 */
@Composable
private fun DefaultBackdropImage(modifier: Modifier = Modifier, fallbackAlpha: Float) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(id = eu.kanade.tachiyomi.R.drawable.backdrop_default),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        alpha = fallbackAlpha,
    )
}

private fun loaderFor(context: android.content.Context, platform: ContentPlatform?) = when (platform) {
    ContentPlatform.PICACG -> PicacgImageLoader.get(context)
    ContentPlatform.JM -> JmCoverImageLoader.get(context)
    else -> com.zycomic.app.platform.PlatformImageLoader.get(context)
}

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
