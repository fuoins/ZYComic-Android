package com.zycomic.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.sin
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * 开屏页（测速中）：极光背景 + 品牌圆环 + 平台胶囊。
 *
 * 进入条件已经是“每个平台都有可用线路和图源”，所以不再逐项打勾；
 * 圆环的三段弧分别表示一个平台的线路/图源完成度，胶囊只用于开关与状态提示。
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
    val platforms = ContentPlatform.entries
    val enabled = platforms.filter { StartupPlatformPolicy.isEnabled(policy, it) }
    val readyCount = enabled.count { platformProgress(it, checks) >= 1f }
    val full = fullRequested || mode == StartupTestMode.FULL

    StartupShell(
        topInfo = networkInfo,
        notice = configUpdateVersion?.let { "网络配置已更新到 v$it，本轮按新配置测速" },
        arcs = platforms.map { PlatformArc(it, platformProgress(it, checks), it in enabled) },
        indeterminate = false,
    ) {
        Text(
            when {
                enabled.isNotEmpty() && readyCount >= enabled.size -> "全部平台就绪"
                full -> "完整测速中"
                else -> "正在挑选最优节点"
            },
            style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 1.sp),
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.92f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                enabled.isEmpty() -> "请在下方至少启用一个平台"
                readyCount >= enabled.size -> "正在进入主界面…"
                else -> "已就绪 $readyCount/${enabled.size} 个平台 · 其余线路后台继续测"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(26.dp))
        PlatformChips(
            platforms = platforms,
            enabled = enabled,
            checks = checks,
            statuses = statuses,
            onToggle = onTogglePlatform,
        )
        Spacer(Modifier.height(12.dp))
        StartupHint()
        summary?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onRetestAll, enabled = !fullRequested) {
            Text(
                stringResource(KMR.strings.startup_retest_all),
                style = MaterialTheme.typography.labelLarge,
                color = if (fullRequested) Color.White.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.86f),
            )
        }
    }
}

/** 首帧配置加载 / 失败页：与测速页共用同一套视觉外壳。 */
@Composable
internal fun StartupConfigGate(
    loading: Boolean,
    detail: String?,
    onRetry: () -> Unit,
) {
    StartupShell(
        topInfo = if (loading) "正在从网络加载线路与图源配置…" else "尚未取得有效的网络配置",
        notice = null,
        arcs = emptyList(),
        indeterminate = loading,
    ) {
        Text(
            if (loading) "正在准备线路" else "网络配置不可用",
            style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 1.sp),
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.92f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (loading) "首次启动需要先取得线路与图源" else (detail ?: "请检查网络后重试"),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        if (!loading) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) {
                Text("重试", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f))
            }
        }
    }
}

// ==================== 视觉外壳 ====================

private data class PlatformArc(val platform: ContentPlatform, val progress: Float, val enabled: Boolean)

@Composable
private fun StartupShell(
    topInfo: String,
    notice: String?,
    arcs: List<PlatformArc>,
    indeterminate: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val accentAlt = MaterialTheme.colorScheme.tertiary
    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackdrop(accent = accent, accentAlt = accentAlt)
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                TopStatusPill(topInfo)
            }
            notice?.let {
                Spacer(Modifier.height(8.dp))
                NoticePill(it)
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HeroRing(arcs = arcs, indeterminate = indeterminate, accent = accent, accentAlt = accentAlt)
                    Spacer(Modifier.height(20.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun AuroraBackdrop(accent: Color, accentAlt: Color) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart),
        label = "auroraPhase",
    )
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "auroraBreathe",
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF0B0B14), Color(0xFF141024), Color(0xFF06060B)),
            ),
        )
        fun bloom(cx: Float, cy: Float, radius: Float, color: Color, drift: Float, strength: Float) {
            val angle = (phase + drift) * 2f * PI.toFloat()
            val x = cx + sin(angle) * size.width * 0.10f
            val y = cy + cos(angle) * size.height * 0.05f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = strength), color.copy(alpha = strength * 0.35f), Color.Transparent),
                    center = Offset(x, y),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(x, y),
            )
        }
        bloom(size.width * 0.18f, size.height * 0.16f, size.width * 0.78f, accent, 0f, 0.30f + breathe * 0.08f)
        bloom(size.width * 0.88f, size.height * 0.34f, size.width * 0.62f, accentAlt, 0.4f, 0.24f + breathe * 0.06f)
        bloom(size.width * 0.42f, size.height * 0.92f, size.width * 0.86f, accent, 0.75f, 0.20f)
        // 底部压暗，保证文字永远可读
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.55f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.55f),
            ),
        )
    }
}

@Composable
private fun TopStatusPill(info: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).background(Color(0xFF6BE39A), CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(
            info.ifBlank { "网络就绪" },
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NoticePill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFFFFC661).copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFFFD79A),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroRing(arcs: List<PlatformArc>, indeterminate: Boolean, accent: Color, accentAlt: Color) {
    val ringSize = 268.dp
    val stroke = 9.dp
    val manwaAccent = rememberContentPlatformAccent(ContentPlatform.MANWA)
    val picacgAccent = rememberContentPlatformAccent(ContentPlatform.PICACG)
    val jmAccent = rememberContentPlatformAccent(ContentPlatform.JM)
    val colors = listOf(manwaAccent, picacgAccent, jmAccent)
    val transition = rememberInfiniteTransition(label = "ring")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4_200, easing = LinearEasing), RepeatMode.Restart),
        label = "ringSpin",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ringPulse",
    )
    // 固定三个槽位，避免平台开关变化时改变 remember 顺序。
    val manwaTarget = arcs.firstOrNull { it.platform == ContentPlatform.MANWA }?.progress ?: 0f
    val picacgTarget = arcs.firstOrNull { it.platform == ContentPlatform.PICACG }?.progress ?: 0f
    val jmTarget = arcs.firstOrNull { it.platform == ContentPlatform.JM }?.progress ?: 0f
    val manwaArc by animateFloatAsState(manwaTarget, tween(700, easing = FastOutSlowInEasing), label = "arcManwa")
    val picacgArc by animateFloatAsState(picacgTarget, tween(700, easing = FastOutSlowInEasing), label = "arcPicacg")
    val jmArc by animateFloatAsState(jmTarget, tween(700, easing = FastOutSlowInEasing), label = "arcJm")
    val animated = listOf(manwaArc, picacgArc, jmArc)
    val drawn = arcs.filter { it.enabled }

    Box(modifier = Modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = stroke.toPx()
            val radius = (size.minDimension - strokePx) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val arcSize = Size(radius * 2, radius * 2)
            val topLeft = Offset(center.x - radius, center.y - radius)
            drawCircle(
                color = Color.White.copy(alpha = 0.07f),
                radius = radius,
                center = center,
                style = Stroke(width = strokePx),
            )
            if (indeterminate) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, accent, accentAlt, Color.Transparent)),
                    startAngle = spin,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            } else if (drawn.isNotEmpty()) {
                val slot = 360f / drawn.size
                val gap = if (drawn.size == 1) 18f else 30f
                drawn.forEachIndexed { index, arc ->
                    val color = colors[arcs.indexOf(arc).coerceAtLeast(0)]
                    val start = -90f + index * slot + gap / 2f
                    val sweep = slot - gap
                    drawArc(
                        color = color.copy(alpha = 0.16f),
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                    val done = animated[arcs.indexOf(arc).coerceAtLeast(0)]
                    if (done > 0.001f) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                listOf(color.copy(alpha = 0.55f), color, Color.White.copy(alpha = 0.92f), color.copy(alpha = 0.55f)),
                            ),
                            startAngle = start,
                            sweepAngle = sweep * done,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round),
                        )
                    }
                }
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.22f * pulse), Color.Transparent),
                    center = center,
                    radius = radius * 0.86f,
                ),
                radius = radius * 0.86f,
                center = center,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LogoMark(accent = accent, accentAlt = accentAlt, pulse = pulse)
            Spacer(Modifier.height(14.dp))
            Text(
                "ZYComic",
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, letterSpacing = 3.sp),
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "漫 画 聚 合 阅 读",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                color = Color.White.copy(alpha = 0.42f),
            )
        }
    }
}

@Composable
private fun LogoMark(accent: Color, accentAlt: Color, pulse: Float) {
    Canvas(modifier = Modifier.size(74.dp)) {
        val w = size.width
        val h = size.height
        val corner = CornerRadius(w * 0.28f, w * 0.28f)
        drawRoundRect(
            brush = Brush.linearGradient(listOf(accent, accentAlt), start = Offset(0f, 0f), end = Offset(w, h)),
            cornerRadius = corner,
            alpha = 0.9f + pulse * 0.1f,
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.26f), Color.Transparent),
                startY = 0f,
                endY = h * 0.7f,
            ),
            cornerRadius = corner,
        )
        val z = Path().apply {
            moveTo(w * 0.27f, h * 0.30f)
            lineTo(w * 0.73f, h * 0.30f)
            lineTo(w * 0.33f, h * 0.70f)
            lineTo(w * 0.75f, h * 0.70f)
        }
        drawPath(path = z, color = Color.White.copy(alpha = 0.95f), style = Stroke(width = w * 0.085f, cap = StrokeCap.Round))
    }
}

@Composable
private fun PlatformChips(
    platforms: List<ContentPlatform>,
    enabled: List<ContentPlatform>,
    checks: List<StartupCheckStatus>,
    statuses: Map<ContentPlatform, PlatformStartupStatus>,
    onToggle: (ContentPlatform, Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        platforms.forEach { platform ->
            val on = platform in enabled
            PlatformChip(
                platform = platform,
                on = on,
                progress = platformProgress(platform, checks),
                running = statuses[platform]?.running == true,
                onClick = { onToggle(platform, !on) },
            )
        }
    }
}

@Composable
private fun PlatformChip(
    platform: ContentPlatform,
    on: Boolean,
    progress: Float,
    running: Boolean,
    onClick: () -> Unit,
) {
    val accent = rememberContentPlatformAccent(platform)
    val alpha = if (on) 1f else 0.38f
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = if (on) 0.07f else 0.03f))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(6.dp).background(
                if (!on) Color.White.copy(alpha = 0.2f) else if (progress >= 1f) accent else accent.copy(alpha = 0.45f),
                CircleShape,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            platformShortName(platform),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.86f * alpha),
        )
        Spacer(Modifier.width(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            phasesOf(platform).forEachIndexed { index, phase ->
                val ready = on && checks.firstOrNull { it.phase == phase }?.result?.isReady == true
                val inProgress = on && !ready && running && progress * 2f > index
                Box(
                    modifier = Modifier.width(9.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(
                        when {
                            ready -> accent
                            inProgress -> accent.copy(alpha = 0.4f)
                            else -> Color.White.copy(alpha = 0.14f)
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun StartupHint() {
    val copy = stringResource(KMR.strings.startup_guidance_copy)
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            copy,
            style = MaterialTheme.typography.labelSmall.copy(lineHeight = 16.sp),
            color = Color.White.copy(alpha = 0.38f),
            textAlign = TextAlign.Center,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.height(30.dp)) {
            Text(
                if (expanded) "收起" else "使用提示",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.45f),
            )
        }
    }
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
