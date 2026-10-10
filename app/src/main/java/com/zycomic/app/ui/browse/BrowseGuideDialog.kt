package com.zycomic.app.ui.browse

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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 「分类」页顶栏 ⓘ 使用说明弹窗。纯静态展示，不涉及任何业务逻辑/网络。
 */
@Composable
fun BrowseGuideDialog(onDismiss: () -> Unit) {
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
            val maxHeight = with(LocalConfiguration.current) {
                (screenHeightDp * 0.68f).dp
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                GuideSection(
                    index = 1,
                    title = "外观",
                    paragraphs = listOf(
                        listOf(
                            Node.T("建议先到"),
                            Node.K("设置"),
                            Node.T("→"),
                            Node.K("外观"),
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
                            Node.K("收藏"),
                            Node.T("、"),
                            Node.K("历史"),
                            Node.T("和"),
                            Node.K("标签屏蔽"),
                            Node.T("需要"),
                            Node.B("注册并登录"),
                            Node.T("后才能使用。登录后建议点一次"),
                            Node.K("gay标签一键屏蔽"),
                            Node.T("（位于"),
                            Node.K("设置"),
                            Node.T("或"),
                            Node.K("我的"),
                            Node.T("页面）。"),
                        ),
                        listOf(
                            Node.T("应用已"),
                            Node.B("默认开启"),
                            Node.K("过滤屏蔽标签"),
                            Node.T("：开启后被屏蔽的漫画会直接隐藏；若手动关闭，这些漫画仍会加载，并以"),
                            Node.K("404"),
                            Node.T("封面占位显示。"),
                        ),
                    ),
                )
                GuideSection(
                    index = 3,
                    title = "分类与筛选",
                    paragraphs = listOf(
                        listOf(
                            Node.K("分类"),
                            Node.T("页顶栏的"),
                            Node.K("筛选"),
                            Node.T("里，"),
                            Node.K("分类"),
                            Node.T("、"),
                            Node.K("最近更新"),
                            Node.T("、"),
                            Node.K("排行"),
                            Node.T("三个标签页各有自己的筛选条件，而"),
                            Node.K("显示"),
                            Node.T("标签页为各页共用。"),
                        ),
                        listOf(
                            Node.K("分类"),
                            Node.T("的性向默认是"),
                            Node.K("一般向"),
                            Node.T("，普通读者建议只选"),
                            Node.K("一般向"),
                            Node.T("或"),
                            Node.K("禁漫"),
                            Node.T("；标签区点击"),
                            Node.K("更多"),
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
                            Node.K("排行"),
                            Node.T("（人气榜 / 新番榜 / 完结榜）内容大多带被屏蔽标签；开启过滤后，为凑满一屏会"),
                            Node.B("连续多翻几页"),
                            Node.T("，可能看起来加载较慢——这通常"),
                            Node.B("不是线路或图源的问题"),
                            Node.T("，如无特别需要可以不看排行榜。"),
                        ),
                        listOf(
                            Node.K("分类"),
                            Node.T("列表默认按"),
                            Node.K("收藏"),
                            Node.T("排序（收藏量优先）。"),
                        ),
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("我知道了") }
        },
    )
}

private sealed class Node {
    /** 普通正文 */
    data class T(val text: String) : Node()
    /** 关键操作词：主题色圆角高亮标签 */
    data class K(val text: String) : Node()
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
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Column {
        paragraphs.forEach { nodes ->
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 30.dp, top = 2.dp, bottom = 4.dp),
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
                        is Node.K -> KeyTerm(node.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyTerm(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
