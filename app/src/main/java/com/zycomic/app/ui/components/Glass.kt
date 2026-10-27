package com.zycomic.app.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃组件库（全 App 统一外观语言）。
 *
 * 设计要点：不依赖背后必须有封面图——玻璃本体是「半透明白渐变 + 主题色淡染 + 顶部内高光
 * + 主题色描边 + 底部投影」，叠在纯白/纯黑页面上也一眼可见；叠在模糊封面上则更通透。
 * 深色/浅色两套参数自动切换。
 */

private data class GlassSpec(
    val glassTop: Color,
    val glassBottom: Color,
    val tint: Color,
    val highlight: Color,
    val edge: Color,
    val shadow: Color,
)

@Composable
private fun glassSpec(tint: Color, tintAlpha: Float): GlassSpec {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return GlassSpec(
        glassTop = Color.White.copy(alpha = if (dark) 0.09f else 0.62f),
        glassBottom = Color.White.copy(alpha = if (dark) 0.035f else 0.40f),
        tint = tint.copy(alpha = if (dark) 0.09f else tintAlpha),
        highlight = Color.White.copy(alpha = if (dark) 0.18f else 0.90f),
        edge = tint.copy(alpha = if (dark) 0.16f else 0.24f),
        shadow = Color.Black.copy(alpha = if (dark) 0.24f else 0.10f),
    )
}

/** 玻璃底板绘制：白玻璃渐变 + 主题色淡染 + 顶部内高光 + 描边。需配合 [clip] 使用。 */
private fun Modifier.glassBase(shape: RoundedCornerShape, spec: GlassSpec): Modifier =
    this.drawBehind {
        drawRect(Brush.verticalGradient(0f to spec.glassTop, 1f to spec.glassBottom))
        drawRect(spec.tint)
        drawLine(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.22f to spec.highlight,
                0.78f to spec.highlight,
                1f to Color.Transparent,
            ),
            start = Offset(size.width * 0.05f, 0.8f),
            end = Offset(size.width * 0.95f, 0.8f),
            strokeWidth = 1.6f,
        )
        drawRoundRect(
            color = spec.edge,
            style = Stroke(width = 1.dp.toPx()),
            cornerRadius = CornerRadius(shape.topStart.x, shape.topStart.y),
        )
    }

/** 独立玻璃卡片（用户信息卡、分组容器等）。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    tint: Color = MaterialTheme.colorScheme.primary,
    tintAlpha: Float = 0.10f,
    elevated: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val spec = glassSpec(tint, tintAlpha)
    Box(
        modifier = modifier
            .then(if (elevated) Modifier.shadow(2.dp, shape, ambientColor = spec.shadow, spotColor = spec.shadow) else Modifier)
            .clip(shape)
            .glassBase(shape, spec),
        content = content,
    )
}

/** 玻璃按钮行（设置项/我的页入口的统一形态：图标徽章 + 标题 + 副标题 + 尾部控件）。 */
@Composable
fun GlassPreferenceRow(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: CharSequence? = null,
    icon: Any? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    widget: @Composable (() -> Unit)? = null,
    onPreferenceClick: (() -> Unit)? = null,
) {
    GlassCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        tint = iconTint,
    ) {
        Row(
            modifier = Modifier
                .clickable(enabled = onPreferenceClick != null) { onPreferenceClick?.invoke() }
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                val spec = glassSpec(iconTint, 0.16f)
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .glassBase(RoundedCornerShape(13.dp), spec),
                    contentAlignment = Alignment.Center,
                ) {
                    when (icon) {
                        is ImageVector -> Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(21.dp))
                        is Painter -> Icon(painter = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(21.dp))
                    }
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

/** 玻璃开关行（设置项开关的统一形态）。 */
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
            Switch(
                checked = checked,
                onCheckedChange = { onCheckedChanged(it) },
                modifier = Modifier.padding(start = 8.dp),
            )
        },
    )
}

/** 玻璃顶栏条：通栏玻璃 + 顶部高光 + 底部描边，用于设置页/搜索页顶部。 */
@Composable
fun GlassTopBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val spec = glassSpec(MaterialTheme.colorScheme.primary, 0.10f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(Brush.verticalGradient(0f to spec.glassTop, 1f to spec.glassBottom))
                drawRect(spec.tint)
                drawLine(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.22f to spec.highlight,
                        0.78f to spec.highlight,
                        1f to Color.Transparent,
                    ),
                    start = Offset(size.width * 0.05f, 0.8f),
                    end = Offset(size.width * 0.95f, 0.8f),
                    strokeWidth = 1.6f,
                )
                drawLine(
                    color = spec.edge,
                    start = Offset(0f, size.height - 0.5f),
                    end = Offset(size.width, size.height - 0.5f),
                    strokeWidth = 1.dp.toPx(),
                )
            },
        content = content,
    )
}

/** 玻璃胶囊 Chip（标签栏选中态、平台选择器等）。 */
@Composable
fun GlassChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    content: @Composable BoxScope.() -> Unit,
) {
    val spec = glassSpec(tint, 0.16f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (selected) {
                    Modifier.drawBehind {
                        drawRect(Brush.verticalGradient(0f to spec.glassTop, 1f to spec.glassBottom))
                        drawRect(spec.tint)
                        drawRoundRect(
                            color = spec.edge,
                            style = Stroke(width = 1.dp.toPx()),
                            cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** 玻璃背景 modifier：给任意现有组件（如搜索框容器、底栏）叠一层玻璃。 */
@Composable
fun Modifier.glassSurface(
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    tint: Color = MaterialTheme.colorScheme.primary,
    tintAlpha: Float = 0.10f,
): Modifier = this.clip(shape).glassBase(shape, glassSpec(tint, tintAlpha))
