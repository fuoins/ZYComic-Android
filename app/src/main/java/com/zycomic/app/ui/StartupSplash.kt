package com.zycomic.app.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zycomic.app.net.PlatformStartupStatus
import com.zycomic.app.net.StartupCheckPhase
import com.zycomic.app.net.StartupCheckStatus
import com.zycomic.app.net.StartupTestMode
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import com.zycomic.app.platform.rememberContentPlatformAccent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

private val BrandGreen = Color(0xFF0FAE79)
private val BrandMint = Color(0xFF2FD3A0)
private val BrandAmber = Color(0xFFFFC063)
private val MarkHighlight = Color(0xFFFFE3B0)

/**
 * 开屏页：动态光斑背景 + 液态玻璃面板 + 单一不确定加载。
 *
 * 进入条件已经是“每个平台都有可用线路和图源”，等待时间从几百毫秒到几十秒不等，
 * 所以这里不画任何百分比进度，只有一条不确定的加载状态。
 */
@Composable
internal fun StartupSplash(
    networkInfo: String,
    configUpdateVersion: Int?,
    fullRequested: Boolean,
    mode: StartupTestMode,
    statuses: Map<ContentPlatform, PlatformStartupStatus>,
    checks: List<StartupCheckStatus>,
    summary: String?,
    onTogglePlatform: (ContentPlatform, Boolean) -> Unit,
    onRetestAll: () -> Unit,
) {
    val policy by StartupPlatformPolicy.state.collectAsState()
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val platforms = ContentPlatform.entries
    val enabled = platforms.filter { StartupPlatformPolicy.isEnabled(policy, it) }
    val readyCount = enabled.count { platformProgress(it, checks) >= 1f }
    val full = fullRequested || mode == StartupTestMode.FULL
    val accent = if (dark) Color(0xFF46E7B4) else BrandGreen

    SplashScaffold(
        dark = dark,
        topInfo = networkInfo,
        notice = configUpdateVersion?.let { "网络配置已更新到 v$it，本轮按新配置测速" },
        hero = {
            BrandStack(dark)
            Spacer(Modifier.height(16.dp))
            BrandWordmark(dark, accent)
            Spacer(Modifier.height(8.dp))
            Tagline(dark)
            Spacer(Modifier.height(30.dp))
            LoadingRow(
                dark = dark,
                accent = accent,
                title = when {
                    enabled.isNotEmpty() && readyCount >= enabled.size -> "全部平台就绪"
                    full -> "完整测速中"
                    else -> "正在准备线路与图源"
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    enabled.isEmpty() -> "请在下方至少启用一个平台"
                    readyCount >= enabled.size -> "正在进入主界面…"
                    else -> "首次启动会久一点，之后就秒进"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (dark) Color.White.copy(alpha = 0.52f) else Color(0xFF8B95A2),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                platforms.forEach { platform ->
                    PlatformChip(
                        platform = platform,
                        enabled = platform in enabled,
                        ready = platformProgress(platform, checks) >= 1f,
                        running = statuses[platform]?.running == true,
                        dark = dark,
                        onClick = { onTogglePlatform(platform, platform !in enabled) },
                    )
                }
            }
        },
        bottom = {
            HintPanel(
                dark = dark,
                accent = accent,
                summary = summary,
                fullRequested = fullRequested,
                onRetestAll = onRetestAll,
            )
        },
    )
}

/** 首帧配置加载 / 失败页：同一套视觉外壳，只有文案和重试不同。 */
@Composable
internal fun StartupConfigGate(
    loading: Boolean,
    detail: String?,
    onRetry: () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val accent = if (dark) Color(0xFF8FB7FF) else Color(0xFF2F6FD0)
    SplashScaffold(
        dark = dark,
        topInfo = if (loading) "正在从网络加载线路与图源配置…" else "尚未取得有效的网络配置",
        notice = null,
        hero = {
            BrandStack(dark)
            Spacer(Modifier.height(16.dp))
            BrandWordmark(dark, accent)
            Spacer(Modifier.height(8.dp))
            Tagline(dark)
            Spacer(Modifier.height(30.dp))
            LoadingRow(
                dark = dark,
                accent = accent,
                title = if (loading) "正在准备线路" else "网络配置不可用",
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (loading) "首次启动需要先取得线路与图源" else (detail ?: "请检查网络后重试"),
                style = MaterialTheme.typography.labelSmall,
                color = if (dark) Color.White.copy(alpha = 0.5f) else Color(0xFF8B95A2),
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
            if (!loading) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onRetry) {
                    Text("重试", style = MaterialTheme.typography.labelLarge, color = accent)
                }
            }
        },
        bottom = {},
    )
}

// ==================== 外壳 ====================

@Composable
private fun SplashScaffold(
    dark: Boolean,
    topInfo: String,
    notice: String?,
    hero: @Composable ColumnScope.() -> Unit,
    bottom: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        SplashBackground(dark = dark)
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, top = 54.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopPill(dark = dark, text = topInfo)
            notice?.let {
                Spacer(Modifier.height(9.dp))
                NoticePill(text = it, dark = dark)
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, content = hero)
            }
            Column(modifier = Modifier.fillMaxWidth(), content = bottom)
        }
    }
}

@Composable
private fun SplashBackground(dark: Boolean) {
    val transition = rememberInfiniteTransition(label = "splash-orbs")
    val phaseA = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(23_000, easing = LinearEasing)),
        label = "orbA",
    )
    val phaseB = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(31_000, easing = LinearEasing)),
        label = "orbB",
    )
    val phaseC = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(27_000, easing = LinearEasing)),
        label = "orbC",
    )
    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val spacingPx = with(density) { 9.dp.toPx() }
        val dots = remember(widthPx, heightPx, spacingPx, dark) {
            halftoneDots(
                width = widthPx.toInt().coerceAtLeast(1),
                height = heightPx.toInt().coerceAtLeast(1),
                spacing = spacingPx.coerceAtLeast(1f),
                dark = dark,
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 底色：三段冷暖交错，避免整屏只有一个色温
            drawRect(
                brush = Brush.linearGradient(
                    0f to if (dark) Color(0xFF17131F) else Color(0xFFFFFBF4),
                    0.42f to if (dark) Color(0xFF1B1A33) else Color(0xFFF1F7FF),
                    1f to if (dark) Color(0xFF0B0A11) else Color(0xFFFFF3E8),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                ),
            )
            fun orb(cx: Float, cy: Float, radius: Float, color: Color, alpha: Float, phase: Float, drift: Float) {
                val x = cx + sin(phase) * size.width * drift
                val y = cy + cos(phase) * size.height * drift * 0.6f
                val center = Offset(x, y)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.42f), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
            val boost = if (dark) 0.72f else 1.02f
            // 六个色团：薄荷 / 紫 / 珊瑚橙 / 天蓝 / 粉 / 青，全部偏置且半径差异大
            orb(-0.10f * size.width, -0.08f * size.height, 0.62f * size.width, Color(0xFF2FE3AE), 0.84f * boost, phaseA.value, 0.07f)
            orb(1.02f * size.width, 0.22f * size.height, 0.52f * size.width, Color(0xFFA78BFA), 0.78f * boost, phaseB.value, 0.05f)
            orb(0.72f * size.width, 1.02f * size.height, 0.60f * size.width, Color(0xFFFFA45C), 0.82f * boost, phaseC.value, 0.05f)
            orb(0.94f * size.width, 0.62f * size.height, 0.26f * size.width, Color(0xFF5FB4FF), 0.52f * boost, phaseB.value, 0.04f)
            orb(0.16f * size.width, 0.20f * size.height, 0.24f * size.width, Color(0xFFFF8FB1), 0.40f * boost, phaseC.value, 0.05f)
            orb(0.06f * size.width, 0.56f * size.height, 0.22f * size.width, Color(0xFF6FF0DF), 0.56f * boost, phaseA.value, 0.045f)

            // 一条斜向彩带：把几个色团串起来，画面不再是一块块孤立的圆
            drawRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.28f to Color(0xFF2FE3AE).copy(alpha = if (dark) 0.10f else 0.12f),
                        0.52f to Color(0xFFA78BFA).copy(alpha = if (dark) 0.10f else 0.12f),
                        0.76f to Color(0xFFFFA45C).copy(alpha = if (dark) 0.09f else 0.10f),
                        1f to Color.Transparent,
                    ),
                    start = Offset(0f, size.height * 0.16f),
                    end = Offset(size.width, size.height * 0.86f),
                ),
            )

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
            dots?.let {
                drawImage(image = it, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            }
            // 底部轻微压暗，托住玻璃面板
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.70f to Color.Transparent,
                    1f to Color.Black.copy(alpha = if (dark) 0.32f else 0.08f),
                ),
            )
        }
    }
}

/** 漫画网点：一次性画进位图，运行时只是一张贴图，避免每帧几千个圆。 */
private fun halftoneDots(width: Int, height: Int, spacing: Float, dark: Boolean): ImageBitmap? {
    if (width <= 0 || height <= 0) return null
    return runCatching {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        }
        val cx = width / 2f
        val cy = height * 0.34f
        val maxDistance = hypot(width.toFloat(), height.toFloat()) * 0.55f
        val peakAlpha = if (dark) 34f else 22f
        var y = spacing / 2f
        while (y < height) {
            var x = spacing / 2f
            while (x < width) {
                val weight = ((hypot(x - cx, y - cy) / maxDistance - 0.42f) / 0.54f).coerceIn(0f, 1f)
                if (weight > 0.03f) {
                    paint.alpha = (peakAlpha * weight).toInt().coerceIn(0, 255)
                    canvas.drawCircle(x, y, spacing * 0.11f, paint)
                }
                x += spacing
            }
            y += spacing
        }
        bitmap.asImageBitmap()
    }.getOrNull()
}

// ==================== 品牌区 ====================

@Composable
private fun BrandStack(dark: Boolean) {
    val transition = rememberInfiniteTransition(label = "splash-logo")
    val floaty by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(5_000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "floaty",
    )
    Box(modifier = Modifier.size(108.dp), contentAlignment = Alignment.Center) {
        // 底部品牌光晕：让 logo 像是浮在光上，而不是贴在背景上
        Canvas(modifier = Modifier.width(176.dp).height(64.dp).offset(y = 30.dp)) {
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(
                        BrandMint.copy(alpha = if (dark) 0.30f else 0.34f),
                        BrandGreen.copy(alpha = if (dark) 0.14f else 0.16f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = size.width / 2f,
                ),
            )
        }
        Box(
            modifier = Modifier.size(108.dp).offset(y = (-4f * floaty).dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(80.dp).rotate(-9f).offset(x = (-9).dp, y = 3.dp)
                    .background(
                        BrandMint.copy(alpha = if (dark) 0.16f else 0.20f),
                        RoundedCornerShape(25.dp),
                    ),
            )
            Box(
                modifier = Modifier.size(80.dp).rotate(7f).offset(x = 9.dp, y = (-2).dp)
                    .background(
                        BrandAmber.copy(alpha = if (dark) 0.16f else 0.22f),
                        RoundedCornerShape(25.dp),
                    ),
            )
            Box(
                modifier = Modifier.size(80.dp)
                    .shadow(
                        elevation = if (dark) 18.dp else 22.dp,
                        shape = RoundedCornerShape(25.dp),
                        ambientColor = BrandGreen.copy(alpha = 0.55f),
                        spotColor = BrandGreen.copy(alpha = 0.75f),
                    )
                    .clip(RoundedCornerShape(25.dp))
                    .background(Brush.linearGradient(listOf(BrandGreen, BrandMint, BrandAmber)))
                    .drawBehind {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.34f),
                                0.6f to Color.Transparent,
                            ),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                BookMark(
                    modifier = Modifier.size(56.dp),
                    page = Color.White,
                    highlight = MarkHighlight,
                )
            }
        }
    }
}

@Composable
private fun BrandWordmark(dark: Boolean, accent: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            "ZY",
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 33.sp, letterSpacing = 1.sp),
            fontWeight = FontWeight.ExtraBold,
            color = if (dark) Color.White else Color(0xFF10151C),
        )
        Text(
            "Comic",
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 33.sp, letterSpacing = 1.sp),
            fontWeight = FontWeight.ExtraBold,
            color = accent,
        )
    }
}

@Composable
private fun Tagline(dark: Boolean) {
    val color = if (dark) Color.White.copy(alpha = 0.48f) else Color(0xFF8D97A5)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(20.dp).height(1.dp).background(color.copy(alpha = 0.4f)))
        Spacer(Modifier.width(10.dp))
        Text(
            "漫 画 聚 合 阅 读",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp),
            color = color,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(20.dp).height(1.dp).background(color.copy(alpha = 0.4f)))
    }
}

/** 品牌标记：两页书 + 书脊留缝 + 右页折角高光。 */
@Composable
private fun BookMark(
    modifier: Modifier = Modifier,
    page: Color,
    highlight: Color,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val left = Path().apply {
            moveTo(w * 0.49f, h * 0.40f)
            cubicTo(w * 0.43f, h * 0.35f, w * 0.33f, h * 0.32f, w * 0.24f, h * 0.35f)
            lineTo(w * 0.24f, h * 0.68f)
            cubicTo(w * 0.33f, h * 0.65f, w * 0.43f, h * 0.67f, w * 0.49f, h * 0.72f)
            close()
        }
        val right = Path().apply {
            moveTo(w * 0.51f, h * 0.40f)
            cubicTo(w * 0.57f, h * 0.35f, w * 0.67f, h * 0.32f, w * 0.76f, h * 0.35f)
            lineTo(w * 0.76f, h * 0.68f)
            cubicTo(w * 0.67f, h * 0.65f, w * 0.57f, h * 0.67f, w * 0.51f, h * 0.72f)
            close()
        }
        val folded = Path().apply {
            moveTo(w * 0.51f, h * 0.40f)
            cubicTo(w * 0.57f, h * 0.35f, w * 0.67f, h * 0.32f, w * 0.76f, h * 0.35f)
            lineTo(w * 0.76f, h * 0.43f)
            cubicTo(w * 0.67f, h * 0.40f, w * 0.57f, h * 0.42f, w * 0.51f, h * 0.47f)
            close()
        }
        drawPath(left, color = page)
        drawPath(right, color = page)
        drawPath(folded, color = highlight)
    }
}

// ==================== 加载与状态 ====================

@Composable
private fun LoadingRow(dark: Boolean, accent: Color, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = accent,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(9.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.3.sp),
            fontWeight = FontWeight.SemiBold,
            color = if (dark) Color.White.copy(alpha = 0.94f) else Color(0xFF2B3340),
        )
    }
}

@Composable
private fun TopPill(dark: Boolean, text: String) {
    Box {
        GlassBox(dark = dark, corner = 999.dp, elevation = 6.dp) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(Color(0xFF17B981), CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(
                    text.ifBlank { "已连接" },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dark) Color.White.copy(alpha = 0.78f) else Color(0xFF46505F),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NoticePill(text: String, dark: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(BrandAmber.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (dark) Color(0xFFFFD79A) else Color(0xFF8A5A12),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ==================== 平台开关 ====================

@Composable
private fun PlatformChip(
    platform: ContentPlatform,
    enabled: Boolean,
    ready: Boolean,
    running: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val accent = rememberContentPlatformAccent(platform)
    val dotColor = when {
        !enabled -> if (dark) Color.White.copy(alpha = 0.22f) else Color(0xFFC6CED8)
        ready -> accent
        running -> accent.copy(alpha = 0.55f)
        else -> accent.copy(alpha = 0.4f)
    }
    GlassBox(
        dark = dark,
        corner = 16.dp,
        elevation = if (enabled) 12.dp else 5.dp,
        tint = if (enabled) accent else null,
    ) {
        Row(
            modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(7.dp).background(dotColor, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                platformShortName(platform),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    enabled -> if (dark) Color(0xFFF0FBF6) else Color(0xFF17212C)
                    else -> if (dark) Color.White.copy(alpha = 0.50f) else Color(0xFF97A0AC)
                },
            )
            Spacer(Modifier.width(9.dp))
            TrackSwitch(checked = enabled, dark = dark)
        }
    }
}

@Composable
private fun TrackSwitch(checked: Boolean, dark: Boolean) {
    Box(
        modifier = Modifier
            .width(30.dp)
            .height(17.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
                when {
                    checked -> Brush.verticalGradient(listOf(Color(0xFF17C48C), Color(0xFF0A9C6A)))
                    dark -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.12f)))
                    else -> Brush.verticalGradient(listOf(Color(0xFFDCE1E8), Color(0xFFCDD4DD)))
                },
            ),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 2.5.dp)
                .size(12.dp)
                .shadow(1.dp, CircleShape)
                .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFF2F4F8))), CircleShape),
        )
    }
}

// ==================== 底部提示 ====================

@Composable
private fun HintPanel(
    dark: Boolean,
    accent: Color,
    summary: String?,
    fullRequested: Boolean,
    onRetestAll: () -> Unit,
) {
    val copy = stringResource(KMR.strings.startup_guidance_copy)
    GlassBox(dark = dark, corner = 22.dp, elevation = 16.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp)) {
            // 提示全文直接展开，不再折叠
            copy.split("\n").filter { it.isNotBlank() }.forEachIndexed { index, line ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 18.sp),
                    fontWeight = if (index == 0) FontWeight.Medium else FontWeight.Normal,
                    color = when {
                        dark && index == 0 -> Color.White.copy(alpha = 0.92f)
                        dark -> Color.White.copy(alpha = 0.64f)
                        index == 0 -> Color(0xFF414A58)
                        else -> Color(0xFF606A78)
                    },
                )
            }
            summary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 16.sp),
                    color = if (dark) Color.White.copy(alpha = 0.40f) else Color(0xFF98A1AD),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                RetestButton(dark = dark, accent = accent, busy = fullRequested, onClick = onRetestAll)
            }
        }
    }
}

/** 底部唯一的主操作：居中、实心胶囊，不用文字链接（单链接居中最容易显得空）。 */
@Composable
private fun RetestButton(dark: Boolean, accent: Color, busy: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                brush = if (busy) {
                    Brush.linearGradient(listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.26f)))
                } else {
                    Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.84f)))
                },
            )
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (busy) "完整测速中…" else stringResource(KMR.strings.startup_retest_all),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.4.sp),
            fontWeight = FontWeight.SemiBold,
            color = if (dark) Color(0xFF06231A) else Color.White,
        )
    }
}

// ==================== 液态玻璃 ====================

@Composable
private fun GlassBox(
    dark: Boolean,
    corner: Dp,
    elevation: Dp,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    val glow = tint ?: BrandGreen
    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = glow.copy(alpha = if (dark) 0.26f else 0.20f),
                spotColor = glow.copy(alpha = if (dark) 0.40f else 0.30f),
            )
            .clip(shape)
            .drawBehind {
                val radius = CornerRadius(corner.toPx(), corner.toPx())
                // 玻璃本体：一层干净的白（深色一层淡白）＋ 一层极淡的平台色。
                // 之前叠了四层白，浅色背景上白叠白必然发灰，这就是“胶囊脏”的原因。
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) 0.13f else 0.74f),
                            Color.White.copy(alpha = if (dark) 0.05f else 0.55f),
                        ),
                    ),
                    cornerRadius = radius,
                )
                tint?.let {
                    drawRoundRect(
                        color = it.copy(alpha = if (dark) 0.06f else 0.09f),
                        cornerRadius = radius,
                    )
                }
                // 顶部高光只在 40% 高度内淡出，不再是一整块径向亮区（那块亮区会形成一个内框）
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (dark) 0.26f else 0.88f),
                        0.14f to Color.White.copy(alpha = if (dark) 0.09f else 0.30f),
                        0.42f to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                    cornerRadius = radius,
                )
                // 液面反光：一条宽而淡的斜向亮带
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.34f to Color.White.copy(alpha = 0f),
                            0.48f to Color.White.copy(alpha = if (dark) 0.09f else 0.18f),
                            0.62f to Color.White.copy(alpha = 0f),
                            1f to Color.Transparent,
                        ),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, 0f),
                    ),
                    cornerRadius = radius,
                )
                // 底部折射：一点点厚度，不压灰
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.82f to Color.Transparent,
                        1f to Color.Black.copy(alpha = if (dark) 0.09f else 0.03f),
                    ),
                    cornerRadius = radius,
                )
                // 顶部 1px 内高光：玻璃的“边”，不是描边
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = if (dark) 0.24f else 0.92f),
                            Color.Transparent,
                        ),
                    ),
                    start = Offset(corner.toPx(), 0.7f),
                    end = Offset(size.width - corner.toPx(), 0.7f),
                    strokeWidth = 1.2f,
                )
            },
        content = content,
    )
}

// ==================== 数据映射 ====================

private fun phasesOf(platform: ContentPlatform): List<StartupCheckPhase> = when (platform) {
    ContentPlatform.MANWA -> listOf(StartupCheckPhase.MANWA_LINE, StartupCheckPhase.MANWA_IMAGE)
    ContentPlatform.PICACG -> listOf(StartupCheckPhase.PICACG_LINE, StartupCheckPhase.PICACG_IMAGE)
    ContentPlatform.JM -> listOf(StartupCheckPhase.JM_LINE, StartupCheckPhase.JM_IMAGE)
}

private fun platformProgress(platform: ContentPlatform, checks: List<StartupCheckStatus>): Float {
    val phases = phasesOf(platform)
    val ready = phases.count { phase -> checks.firstOrNull { it.phase == phase }?.result?.isReady == true }
    return ready.toFloat() / phases.size
}

private fun platformShortName(platform: ContentPlatform): String = when (platform) {
    ContentPlatform.MANWA -> "漫蛙"
    ContentPlatform.PICACG -> "哔咔"
    ContentPlatform.JM -> "禁漫"
}
