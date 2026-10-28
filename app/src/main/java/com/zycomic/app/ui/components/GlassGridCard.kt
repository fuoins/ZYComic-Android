package com.zycomic.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 全站统一的漫画网格卡（收藏页 / 分类页 / 搜索页）。
 *
 * 版式与禁漫收藏网格一致：外层 3dp 间距 + 玻璃卡（12dp 圆角）+ 6dp 内边距 + 3:4 封面。
 * 标题按显示模式：
 * - 0 紧凑：标题叠在封面底部（黑色渐变 + 白字）
 * - 1 舒适：标题放在封面下方，独立玻璃条
 * - 2 仅封面：不显示标题
 *
 * 平台差异只通过 [cover] / [badges] 注入（各自图片加载器、排行角标、未读角标）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassMangaGridCard(
    title: String?,
    displayMode: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    cover: @Composable BoxScope.() -> Unit,
    badges: @Composable BoxScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .padding(2.dp)
            .glassSurface(
                shape = RoundedCornerShape(12.dp),
                radius = 12.dp,
                part = GlassPart.CARD,
                stroke = false,
            )
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick ?: {})
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            cover()
            badges()
            if (displayMode == 0 && !title.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.42f)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color(0xAA000000),
                            ),
                        ),
                )
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                )
            }
        }
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        if (displayMode == 1 && !title.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .glassSurface(
                        shape = RoundedCornerShape(8.dp),
                        radius = 8.dp,
                        part = GlassPart.COVER_NAME,
                        stroke = false,
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                val customText = AppearanceColors.partTextColors.collectAsState().value[GlassPart.COVER_NAME.key]
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = customText ?: LocalContentColor.current,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
