package com.zycomic.app.ui.browse

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewCompact
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

/** 引导芯片图标：优先用 Material 矢量，少数工程图标库缺失的用本地矢量资源。 */
sealed interface GuideIcon {
    data class Vec(val image: ImageVector) : GuideIcon
    data class Res(@DrawableRes val resId: Int) : GuideIcon
}

/** 「分类」页顶栏 ⓘ 使用说明弹窗（弹窗内只读；gay 屏蔽仅指路，不在此执行）。 */
@Composable
fun BrowseGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                Text("使用说明", style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            // 弹窗内不直接执行屏蔽：点击仅指路到「我的」页。
            val gaySlot: @Composable () -> Unit = {
                Spacer(Modifier.height(6.dp))
                GuideActionButton(
                    text = "一键屏蔽gay标签",
                    icon = GuideIcon.Vec(Icons.Outlined.Block),
                    tone = GuideButtonTone.Neutral,
                    onClick = {
                        android.widget.Toast
                            .makeText(context, "请到“我的”页点击“一键屏蔽gay标签”", android.widget.Toast.LENGTH_SHORT)
                            .show()
                    },
                )
            }
            val maxHeight = with(LocalConfiguration.current) { (screenHeightDp * 0.68f).dp }
            BrowseGuideContent(
                modifier = Modifier.fillMaxWidth(),
                maxHeight = maxHeight,
                gayBlockSlot = gaySlot,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("我知道了") }
        },
    )
}

/**
 * 使用说明正文：分类页 ⓘ 弹窗与首启向导第 3 页共享同一份图标/文案。
 *
 * @param maxHeight 非 null 时限制最大高度（弹窗场景）；向导场景传 null 填满。
 * @param gayBlockSlot 第 2 小节「一键屏蔽gay标签」按钮位（弹窗只读指路 / 向导真实可执行）。
 */
@Composable
fun BrowseGuideContent(
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
    gayBlockSlot: (@Composable () -> Unit)? = null,
) {
    var m = modifier
    if (maxHeight != null) m = m.heightIn(max = maxHeight)
    Column(
        modifier = m
            .verticalScroll(rememberScrollState()),
    ) {
        GuideSection(
            index = 1,
            title = "外观",
            paragraphs = listOf(
                listOf(
                    Node.T("建议先到"),
                    Node.K("设置", GuideIcon.Vec(Icons.Filled.Settings)),
                    Node.T("→"),
                    Node.K("外观", GuideIcon.Vec(Icons.Outlined.Palette)),
                    Node.T("，选择你喜欢的主题与显示样式。"),
                ),
            ),
        )
        GuideSection(
            index = 2,
            title = "账号与屏蔽",
            warning = true,
            paragraphs = listOf(
                listOf(
                    Node.K("收藏", GuideIcon.Vec(Icons.Outlined.FavoriteBorder)),
                    Node.T("、"),
                    Node.K("历史", GuideIcon.Vec(Icons.Filled.History)),
                    Node.T("和"),
                    Node.K("标签屏蔽", GuideIcon.Vec(Icons.Outlined.Block)),
                    Node.T("需要"),
                    Node.B("注册并登录"),
                    Node.T("后才能使用。登录后可一键屏蔽gay相关标签："),
                ),
                listOf(
                    Node.T("应用已"),
                    Node.B("默认开启"),
                    Node.K("过滤屏蔽标签", GuideIcon.Vec(Icons.Outlined.FilterList)),
                    Node.T("：开启后被屏蔽的漫画会直接隐藏；若手动关闭，这些漫画仍会加载，并以"),
                    Node.K("404", GuideIcon.Vec(Icons.Outlined.Warning)),
                    Node.T("封面占位显示。"),
                ),
            ),
            footerSlot = gayBlockSlot,
        )
        GuideSection(
            index = 3,
            title = "分类与筛选",
            paragraphs = listOf(
                listOf(
                    Node.K("分类", GuideIcon.Vec(Icons.Filled.Home)),
                    Node.T("页顶栏的"),
                    Node.K("筛选", GuideIcon.Vec(Icons.Outlined.FilterList)),
                    Node.T("里，"),
                    Node.K("分类", GuideIcon.Vec(Icons.Filled.Home)),
                    Node.T("、"),
                    Node.K("最近更新", GuideIcon.Vec(Icons.Outlined.Schedule)),
                    Node.T("、"),
                    Node.K("排行", GuideIcon.Res(R.drawable.ic_leaderboard_24dp)),
                    Node.T("三个标签页各有自己的筛选条件，而"),
                    Node.K("显示", GuideIcon.Vec(Icons.Filled.ViewCompact)),
                    Node.T("标签页为各页共用。"),
                ),
                listOf(
                    Node.K("分类", GuideIcon.Vec(Icons.Filled.Home)),
                    Node.T("的性向默认是"),
                    Node.K("一般向", GuideIcon.Vec(Icons.Outlined.PersonOutline)),
                    Node.T("，普通读者建议只选"),
                    Node.K("一般向", GuideIcon.Vec(Icons.Outlined.PersonOutline)),
                    Node.T("或"),
                    Node.K("禁漫", GuideIcon.Vec(Icons.Outlined.Lock)),
                    Node.T("；标签区点击"),
                    Node.K("更多", GuideIcon.Vec(Icons.Filled.ExpandMore)),
                    Node.T("可展开完整标签列表。"),
                ),
            ),
        )
        GuideSection(
            index = 4,
            title = "排行榜与排序",
            warning = true,
            paragraphs = listOf(
                listOf(
                    Node.K("排行", GuideIcon.Res(R.drawable.ic_leaderboard_24dp)),
                    Node.T("（人气榜 / 新番榜 / 完结榜）内容大多带被屏蔽标签；开启过滤后，为凑满一屏会"),
                    Node.B("连续多翻几页"),
                    Node.T("，可能看起来加载较慢——这通常"),
                    Node.B("不是"),
                    Node.K("线路", GuideIcon.Res(R.drawable.ic_route_24dp)),
                    Node.T("或"),
                    Node.K("图源", GuideIcon.Res(R.drawable.ic_image_24dp)),
                    Node.T("的问题，如无特别需要可以不看排行榜。"),
                ),
                listOf(
                    Node.K("分类", GuideIcon.Vec(Icons.Filled.Home)),
                    Node.T("列表默认按"),
                    Node.K("收藏", GuideIcon.Vec(Icons.Filled.Star)),
                    Node.T("排序（收藏量优先）。"),
                ),
            ),
        )
    }
}

/** 引导内的操作按钮（一键屏蔽gay标签等）。 */
enum class GuideButtonTone { Primary, Success, Neutral }

@Composable
fun GuideActionButton(
    text: String,
    icon: GuideIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: GuideButtonTone = GuideButtonTone.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            when (icon) {
                is GuideIcon.Vec -> Icon(icon.image, contentDescription = null, modifier = Modifier.size(20.dp))
                is GuideIcon.Res -> Icon(painterResource(icon.resId), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(text, fontWeight = FontWeight.Bold)
    }
    val m = modifier.fillMaxWidth()
    when (tone) {
        GuideButtonTone.Primary -> Button(onClick = onClick, enabled = enabled && !loading, modifier = m) { content() }
        GuideButtonTone.Success -> Button(
            onClick = onClick,
            enabled = false,
            modifier = m,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(Icons.Filled.DoneAll, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(text, fontWeight = FontWeight.Bold)
        }
        GuideButtonTone.Neutral -> OutlinedButton(onClick = onClick, enabled = enabled && !loading, modifier = m) { content() }
    }
}

private sealed class Node {
    /** 普通正文 */
    data class T(val text: String) : Node()
    /** 关键操作词：图标 + 文字圆角高亮标签 */
    data class K(val text: String, val icon: GuideIcon? = null) : Node()
    /** 需醒目的普通加粗词（暖色小节里用于强调） */
    data class B(val text: String) : Node()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuideSection(
    index: Int,
    title: String,
    paragraphs: List<List<Node>>,
    warning: Boolean = false,
    footerSlot: (@Composable () -> Unit)? = null,
) {
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    if (warning) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$index",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (warning) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (warning) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            if (warning) {
                Spacer(Modifier.size(4.dp))
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Column(modifier = Modifier.padding(start = 30.dp)) {
        paragraphs.forEach { nodes ->
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                nodes.forEach { node ->
                    when (node) {
                        is Node.T -> Text(
                            node.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        is Node.B -> Text(
                            node.text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        is Node.K -> GuideChip(label = node.text, icon = node.icon)
                    }
                }
            }
        }
        footerSlot?.invoke()
    }
}

@Composable
private fun GuideChip(label: String, icon: GuideIcon?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        if (icon != null) {
            val tint = MaterialTheme.colorScheme.onPrimaryContainer
            when (icon) {
                is GuideIcon.Vec -> Icon(icon.image, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
                is GuideIcon.Res -> Icon(painterResource(icon.resId), contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.size(3.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
