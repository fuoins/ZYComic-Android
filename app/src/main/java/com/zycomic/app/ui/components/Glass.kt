package com.zycomic.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * 液态玻璃组件库（全 App 统一外观语言，但**每类部件参数独立**）。
 *
 * 真 backdrop blur：主界面把背景层标记为模糊源（[LocalAppHaze]），玻璃通过 Haze 对
 * 玻璃后面的背景做实时模糊（Android 12+ RenderEffect），再叠白玻璃 + 可选描边。
 * 底栏/顶栏/按钮/标签/封面名字条/搜索框各自有独立的模糊度与透明度（外观页可调）。
 */

/** 主界面提供的全局模糊源；玻璃组件从这里取 HazeState。 */
val LocalAppHaze = staticCompositionLocalOf<HazeState?> { null }

@Composable
private fun glassDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** 当前部件的玻璃参数：模糊半径 + 白玻璃透明度（都乘全局强度）。 */
@Composable
private fun resolveGlass(part: GlassPart, blurOverride: Dp?, alphaOverride: Float?): Pair<Dp, Float> {
    val strength by GlassPrefs.glassStrength.collectAsState()
    val partBlur by GlassPrefs.partBlur.collectAsState()
    val partAlpha by GlassPrefs.partAlpha.collectAsState()
    val blur = blurOverride ?: (partBlur[part.key] ?: part.defaultBlurDp).dp
    val alpha = (alphaOverride ?: (partAlpha[part.key] ?: part.defaultAlpha)) * strength
    return blur to alpha
}

/**
 * 玻璃外壳：真 backdrop blur（Haze）+ 淡白玻璃 + 可选平台色淡染/描边。
 * 无 HazeState 时退化为纯渐变玻璃（保证可见）。
 */
@Composable
fun GlassShell(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    radius: Dp = 18.dp,
    part: GlassPart = GlassPart.BUTTON,
    tintLayer: Color = Color.Transparent,
    stroke: Boolean = false,
    alphaOverride: Float? = null,
    blurOverride: Dp? = null,
    elevated: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val hazeState = LocalAppHaze.current
    val dark = glassDark()
    val (blur, alpha) = resolveGlass(part, blurOverride, alphaOverride)
    val radiusPx = with(LocalDensity.current) { radius.toPx() }
    val edge = Color.White.copy(alpha = if (dark) 0.16f else 0.55f)
    val shadowColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.05f)
    // Haze 正常时白玻璃只作薄高光层；无 Haze 时它就是玻璃本体。
    val gradientTop = if (hazeState != null) alpha * 0.38f else alpha
    val gradientBottom = if (hazeState != null) alpha * 0.18f else alpha * 0.55f
    Box(
        modifier = modifier
            .then(if (elevated) Modifier.shadow(2.dp, shape, ambientColor = shadowColor, spotColor = shadowColor) else Modifier)
            .clip(shape)
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            backgroundColor = Color.Transparent,
                            tint = HazeTint(Color.White.copy(alpha = alpha), BlendMode.SrcOver),
                            blurRadius = blur,
                            noiseFactor = 0f,
                            // 平台不支持真模糊时用 scrim 兜底，保证玻璃可见。
                            fallbackTint = HazeTint(Color.White.copy(alpha = alpha), BlendMode.SrcOver),
                        ),
                    )
                } else {
                    Modifier
                },
            )
            .drawBehind {
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = gradientTop), 1f to Color.White.copy(alpha = gradientBottom)))
                if (tintLayer.alpha > 0f) drawRect(tintLayer)
                if (stroke) {
                    drawRoundRect(
                        color = edge,
                        style = Stroke(width = 1.dp.toPx()),
                        cornerRadius = CornerRadius(radiusPx, radiusPx),
                    )
                }
            },
        content = content,
    )
}

/** 独立玻璃卡片（用户信息卡、分组容器等）。[accent] 非空时叠加平台色淡染。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 18.dp,
    part: GlassPart = GlassPart.CARD,
    accent: Color? = null,
    accentAlpha: Float = 0.10f,
    elevated: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassShell(
        modifier = modifier,
        shape = RoundedCornerShape(radius),
        radius = radius,
        part = part,
        tintLayer = accent?.copy(alpha = accentAlpha) ?: Color.Transparent,
        elevated = elevated,
        content = content,
    )
}

/** 玻璃按钮行（设置项/我的页入口的统一形态：图标 + 标题 + 副标题 + 尾部控件）。 */
@Composable
fun GlassPreferenceRow(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: CharSequence? = null,
    icon: Any? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    widget: @Composable (() -> Unit)? = null,
    onPreferenceClick: (() -> Unit)? = null,
) {
    GlassCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        radius = 18.dp,
        part = GlassPart.BUTTON,
    ) {
        Row(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = onPreferenceClick != null,
                    onClick = { onPreferenceClick?.invoke() },
                )
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                when (icon) {
                    is ImageVector -> Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                    is Painter -> Icon(painter = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = if (icon != null) 12.dp else 0.dp)) {
                if (!title.isNullOrBlank()) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 10,
                    )
                }
            }
            widget?.invoke()
        }
    }
}

/** 玻璃开关行（设置项开关的统一形态；开关用中性对比色，开启一眼可见）。 */
@Composable
fun GlassSwitchRow(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: CharSequence? = null,
    icon: Any? = null,
    checked: Boolean = false,
    onCheckedChanged: (Boolean) -> Unit,
) {
    GlassPreferenceRow(
        modifier = modifier,
        title = title,
        subtitle = subtitle,
        icon = icon,
        onPreferenceClick = { onCheckedChanged(!checked) },
        widget = {
            val dark = glassDark()
            Switch(
                checked = checked,
                onCheckedChange = { onCheckedChanged(it) },
                modifier = Modifier.padding(start = 8.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = if (dark) Color(0xFF20222A) else Color.White,
                    checkedTrackColor = if (dark) Color.White.copy(alpha = 0.75f) else Color(0xFF3A3F4B),
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = if (dark) Color.White.copy(alpha = 0.9f) else Color.White,
                    uncheckedTrackColor = if (dark) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.16f),
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        },
    )
}

/** 玻璃顶栏条：真磨砂通栏玻璃 + 顶部高光 + 底部描边。 */
@Composable
fun GlassTopBar(
    modifier: Modifier = Modifier,
    bottomLine: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val dark = glassDark()
    val highlight = Color.White.copy(alpha = if (dark) 0.22f else 0.78f)
    val edge = Color.White.copy(alpha = if (dark) 0.16f else 0.55f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(0.dp), radius = 0.dp, part = GlassPart.TOP_BAR, stroke = false)
            .drawBehind {
                drawLine(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.22f to highlight,
                        0.78f to highlight,
                        1f to Color.Transparent,
                    ),
                    start = Offset(size.width * 0.05f, 0.8f),
                    end = Offset(size.width * 0.95f, 0.8f),
                    strokeWidth = 1.6f,
                )
                if (bottomLine) {
                    drawLine(
                        color = edge,
                        start = Offset(0f, size.height - 0.5f),
                        end = Offset(size.width, size.height - 0.5f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            },
        content = content,
    )
}

/** 玻璃胶囊 Chip（标签栏、平台选择器等）。[accent] 非空时叠加平台色淡染。 */
@Composable
fun GlassChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    part: GlassPart = GlassPart.TAB,
    accent: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val tintLayer = accent?.copy(alpha = 0.14f) ?: Color.Transparent
    if (!selected) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
            content = content,
        )
        return
    }
    GlassShell(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        radius = 999.dp,
        part = part,
        tintLayer = tintLayer,
        stroke = true,
        elevated = false,
        content = {
            Box(
                modifier = Modifier
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
                content = content,
            )
        },
    )
}

/** 玻璃背景 modifier：给任意现有组件（搜索框、操作条、标签胶囊、封面名字条）叠真磨砂玻璃。 */
@Composable
fun Modifier.glassSurface(
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    radius: Dp = 14.dp,
    part: GlassPart = GlassPart.BUTTON,
    accent: Color? = null,
    accentAlpha: Float = 0.10f,
    stroke: Boolean = true,
    alphaOverride: Float? = null,
    blurOverride: Dp? = null,
): Modifier {
    val hazeState = LocalAppHaze.current
    val dark = glassDark()
    val (blur, alpha) = resolveGlass(part, blurOverride, alphaOverride)
    val radiusPx = with(LocalDensity.current) { radius.toPx() }
    val tintLayer = accent?.copy(alpha = accentAlpha) ?: Color.Transparent
    val edge = Color.White.copy(alpha = if (dark) 0.16f else 0.55f)
    val gradientTop = if (hazeState != null) alpha * 0.38f else alpha
    val gradientBottom = if (hazeState != null) alpha * 0.18f else alpha * 0.55f
    return this
        .clip(shape)
        .then(
            if (hazeState != null) {
                Modifier.hazeEffect(
                    state = hazeState,
                    style = HazeStyle(
                        backgroundColor = Color.Transparent,
                        tint = HazeTint(Color.White.copy(alpha = alpha), BlendMode.SrcOver),
                        blurRadius = blur,
                        noiseFactor = 0f,
                        fallbackTint = HazeTint(Color.White.copy(alpha = alpha), BlendMode.SrcOver),
                    ),
                )
            } else {
                Modifier
            },
        )
        .drawBehind {
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = gradientTop), 1f to Color.White.copy(alpha = gradientBottom)))
            if (tintLayer.alpha > 0f) drawRect(tintLayer)
            if (stroke) {
                drawRoundRect(
                    color = edge,
                    style = Stroke(width = 1.dp.toPx()),
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                )
            }
        }
}
