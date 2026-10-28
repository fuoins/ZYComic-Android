package com.zycomic.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 预览浮窗里的常用部件（其余部件去完整外观设置里调）。 */
private val QUICK_PARTS = listOf(
    GlassPart.BOTTOM_BAR,
    GlassPart.TOP_BAR,
    GlassPart.BUTTON,
    GlassPart.CARD,
    GlassPart.TAB_SELECTED,
    GlassPart.COVER_NAME,
)

private data class QuickColorTarget(
    val title: String,
    val current: androidx.compose.ui.graphics.Color,
    val apply: (android.content.Context, androidx.compose.ui.graphics.Color) -> Unit,
)

/**
 * 外观预览浮窗：不离开主界面就能调最常用的几项。
 * 标题栏可「最小化」回悬浮按钮，或「打开完整外观设置」。
 */
@Composable
fun AppearanceQuickPanel(
    modifier: Modifier = Modifier,
    onMinimize: () -> Unit,
    onOpenFull: () -> Unit,
) {
    val context = LocalContext.current
    val backdropBlur by GlassPrefs.backdropBlurDp.collectAsState()
    val backdropAlpha by GlassPrefs.backdropAlpha.collectAsState()
    val strength by GlassPrefs.glassStrength.collectAsState()
    val partAlpha by GlassPrefs.partAlpha.collectAsState()
    val partFrosted by GlassPrefs.partFrosted.collectAsState()
    val uiAccent by AppearanceColors.uiAccent.collectAsState()
    val switchColor by AppearanceColors.switchColor.collectAsState()
    var colorTarget by remember { mutableStateOf<QuickColorTarget?>(null) }

    GlassCard(modifier = modifier.width(316.dp), radius = 20.dp, part = GlassPart.CARD) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 2.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(17.dp))
                Text(
                    "外观调整",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                IconButton(onClick = onMinimize) {
                    Icon(Icons.Outlined.Remove, contentDescription = "最小化")
                }
                IconButton(onClick = onOpenFull) {
                    Icon(Icons.Outlined.OpenInFull, contentDescription = "打开完整外观设置")
                }
            }
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .heightIn(max = 372.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            ) {
                QuickSlider(
                    label = "背景模糊",
                    valueText = "${backdropBlur.toInt()} dp",
                    value = backdropBlur,
                    range = 0f..24f,
                    onChange = { GlassPrefs.setBackdropBlurDp(context, it) },
                )
                QuickSlider(
                    label = "背景透明度",
                    valueText = "${(backdropAlpha * 100).toInt()}%",
                    value = backdropAlpha,
                    range = 0.3f..1f,
                    onChange = { GlassPrefs.setBackdropAlpha(context, it) },
                )
                QuickSlider(
                    label = "玻璃强度",
                    valueText = "${(strength * 100).toInt()}%",
                    value = strength,
                    range = 0f..1f,
                    onChange = { GlassPrefs.setGlassStrength(context, it) },
                )
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                val accentFallback = MaterialTheme.colorScheme.primary
                val accentColor = uiAccent ?: accentFallback
                val switchShownColor = switchColor ?: accentFallback
                QuickColorRow(
                    label = "界面主色",
                    color = accentColor,
                ) {
                    colorTarget = QuickColorTarget(
                        title = "界面主色",
                        current = accentColor,
                    ) { ctx, color -> AppearanceColors.setUiAccent(ctx, color) }
                }
                QuickColorRow(
                    label = "开关颜色",
                    color = switchShownColor,
                ) {
                    colorTarget = QuickColorTarget(
                        title = "开关颜色",
                        current = switchShownColor,
                    ) { ctx, color -> AppearanceColors.setSwitchColor(ctx, color) }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(
                    "常用部件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QUICK_PARTS.forEach { part ->
                    val alpha = partAlpha[part.key] ?: part.defaultAlpha
                    val frosted = partFrosted[part.key] ?: true
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            part.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (frosted) "磨砂 ${(alpha * 100).toInt()}%" else "无磨砂 ${(alpha * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Switch(
                            checked = frosted,
                            onCheckedChange = { GlassPrefs.setPartFrosted(context, part, it) },
                        )
                    }
                    Slider(
                        value = alpha,
                        onValueChange = { GlassPrefs.setPartAlpha(context, part, it) },
                        valueRange = 0f..1f,
                    )
                }
                TextButton(onClick = onOpenFull, modifier = Modifier.fillMaxWidth()) {
                    Text("全部外观设置")
                }
            }
        }
    }

    colorTarget?.let { target ->
        ColorPickerDialog(
            title = target.title,
            initial = target.current,
            onDismiss = { colorTarget = null },
            onConfirm = { color ->
                target.apply(context, color)
                colorTarget = null
            },
        )
    }
}

@Composable
private fun QuickSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun QuickColorRow(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                    shape = CircleShape,
                ),
        )
    }
}
