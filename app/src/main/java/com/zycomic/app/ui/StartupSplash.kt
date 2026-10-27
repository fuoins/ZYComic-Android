package com.zycomic.app.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.runtime.mutableStateOf
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
            drawRect(
                Brush.verticalGradient(
                    if (dark) {
                        listOf(Color(0xFF15151D), Color(0xFF191627), Color(0xFF0D0C13))
                    } else {
                        listOf(Color(0xFFFDFBF7), Color(0xFFF7F4FB), Color(0xFFFFF8F0))
                    },
                ),
            )
            // 一大两中两小，全部偏置，避免“均匀铺满”的配色图感
            fun orb(cx: Float, cy: Float, radius: Float, color: Color, alpha: Float, phase: Float, drift: Float) {
                val x = cx + sin(phase) * size.width * drift
                val y = cy + cos(phase) * size.height * drift * 0.6f
                val center = Offset(x, y)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
            val boost = if (dark) 0.72f else 1f
            orb(-0.09f * size.width, -0.10f * size.height, 0.55f * size.width, Color(0xFF3FE0B2), 0.80f * boost, phaseA.value, 0.06f)
            orb(1.05f * size.width, 0.26f * size.height, 0.46f * size.width, Color(0xFF9F86FF), 0.66f * boost, phaseB.value, 0.05f)
            orb(0.77f * size.width, 1.04f * size.height, 0.54f * size.width, Color(0xFFFFA75E), 0.78f * boost, phaseC.value, 0.045f)
            orb(0.87f * size.width, 0.59f * size.height, 0.26f * size.width, Color(0xFF5FB4FF), 0.42f * boost, phaseB.value, 0.035f)
            orb(0.10f * size.width, 0.51f * size.height, 0.20f * size.width, Color(0xFF6FF0CF), 0.50f * boost, phaseA.value, 0.04f)

            // 中央柔光，保证文字区域干净
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (dark) 0.06f else 0.34f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.46f, size.height * 0.38f),
                    radius = size.width * 0.95f,
                ),
            )
            dots?.let {
                drawImage(image = it, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            }
            // 底部压暗，托住玻璃面板
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.66f to Color.Transparent,
                    1f to Color.Black.copy(alpha = if (dark) 0.34f else 0.06f),
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
        val peakAlpha = if (dark) 46f else 32f
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
    Box(modifier = Modifier.size(108.dp), contentAlignment = Alignment.Center) {
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
                .shadow(if (dark) 18.dp else 22.dp, RoundedCornerShape(25.dp))
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
    GlassBox(dark = dark, corner = 16.dp, elevation = if (enabled) 12.dp else 6.dp) {
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
                    else -> if (dark) Color.White.copy(alpha = 0.55f) else Color(0xFF8D97A5)
                },
            )
            Spacer(Modifier.width(9.dp))
            TrackSwitch(checked = enabled, dark = dark)
            if (!enabled) {
                Spacer(Modifier.width(5.dp))
                Text(
                    "已关闭",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (dark) Color.White.copy(alpha = 0.38f) else Color(0xFFAAB2BD),
                )
            }
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
    var expanded by remember { mutableStateOf(false) }
    GlassBox(dark = dark, corner = 22.dp, elevation = 14.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 6.dp)) {
            Text(
                if (expanded) copy else copy.substringBefore("\n"),
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 17.5.sp),
                color = if (dark) Color.White.copy(alpha = 0.72f) else Color(0xFF59616E),
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
            summary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dark) Color.White.copy(alpha = 0.42f) else Color(0xFF9AA2AE),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().drawBehind {
                    drawLine(
                        color = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx(),
                    )
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (expanded) "收起" else "使用提示 ›",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                            color = if (dark) Color.White.copy(alpha = 0.66f) else Color(0xFF6F7885),
                        )
                    }
                }
                TextButton(onClick = onRetestAll, enabled = !fullRequested) {
                    Text(
                        stringResource(KMR.strings.startup_retest_all),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = if (fullRequested) accent.copy(alpha = 0.4f) else accent,
                    )
                }
            }
        }
    }
}

// ==================== 液态玻璃 ====================

@Composable
private fun GlassBox(
    dark: Boolean,
    corner: Dp,
    elevation: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .shadow(elevation = elevation, shape = shape)
            .clip(shape)
            .drawBehind {
                val radius = CornerRadius(corner.toPx(), corner.toPx())
                // 底色
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) 0.10f else 0.22f),
                            Color.White.copy(alpha = if (dark) 0.03f else 0.06f),
                        ),
                    ),
                    cornerRadius = radius,
                )
                // 斜向液面反光
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to Color.White.copy(alpha = 0f),
                            0.34f to Color.White.copy(alpha = if (dark) 0.05f else 0.16f),
                            0.48f to Color.White.copy(alpha = if (dark) 0.09f else 0.28f),
                            0.62f to Color.White.copy(alpha = if (dark) 0.05f else 0.16f),
                            1f to Color.White.copy(alpha = 0f),
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height * 0.55f),
                    ),
                    cornerRadius = radius,
                )
                // 顶部弧光
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (dark) 0.14f else 0.55f),
                            Color.White.copy(alpha = if (dark) 0.04f else 0.16f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width / 2f, -0.14f * size.height),
                        radius = 1.25f * size.width,
                    ),
                    cornerRadius = radius,
                )
                // 底部折射暗影：玻璃厚度
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.58f to Color.Transparent,
                        1f to Color.Black.copy(alpha = if (dark) 0.14f else 0.05f),
                    ),
                    cornerRadius = radius,
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
