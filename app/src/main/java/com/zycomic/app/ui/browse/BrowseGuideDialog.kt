package com.zycomic.app.ui.browse

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.ContentPlatformSelector
import com.zycomic.app.platform.rememberContentPlatformAccent

/** 引导芯片图标：优先使用 Material 矢量，少数工程图标用本地资源。 */
sealed interface GuideIcon {
    data class Vec(val image: ImageVector) : GuideIcon
    data class Res(@DrawableRes val resId: Int) : GuideIcon
}

/** 分类页使用说明弹窗；切换只改变说明内容，不会改写平台偏好或发起平台请求。 */
@Composable
fun BrowseGuideDialog(
    onDismiss: () -> Unit,
    initialPlatform: ContentPlatform = ContentPlatform.MANWA,
) {
    val context = LocalContext.current
    var platform by remember(initialPlatform) { mutableStateOf(initialPlatform) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text("使用说明", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                ContentPlatformSelector(
                    selected = platform,
                    onSelect = { platform = it },
                    compact = true,
                )
            }
        },
        text = {
            val maxHeight = with(LocalConfiguration.current) { (screenHeightDp * 0.68f).dp }
            val gaySlot: (@Composable () -> Unit)? = if (platform == ContentPlatform.MANWA) {
                {
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
            } else {
                null
            }
            BrowseGuideContent(
                platform = platform,
                modifier = Modifier.fillMaxWidth(),
                maxHeight = maxHeight,
                gayBlockSlot = gaySlot,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("我知道了") } },
    )
}

/** 三个平台的独立使用说明；由 BrowseGuideDialog 和首启/重看引导共享。 */
@Composable
fun BrowseGuideContent(
    platform: ContentPlatform,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
    gayBlockSlot: (@Composable () -> Unit)? = null,
) {
    val sections = remember(platform) { PlatformGuidePolicy.sections(platform.id) }
    val accent = rememberContentPlatformAccent(platform)
    var contentModifier = modifier
    if (maxHeight != null) contentModifier = contentModifier.heightIn(max = maxHeight)
    Column(
        modifier = contentModifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "以下内容仅介绍当前选择的平台；切换此处的说明不会更改平台设置。页面实际内容受平台服务和网络状态影响。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        sections.forEachIndexed { index, section ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Text(
                    section.body,
                    modifier = Modifier.padding(start = 30.dp, top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        gayBlockSlot?.invoke()
    }
}

/** 向导内的真实操作按钮。 */
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
    val buttonModifier = modifier.fillMaxWidth()
    when (tone) {
        GuideButtonTone.Primary -> Button(onClick = onClick, enabled = enabled && !loading, modifier = buttonModifier) { content() }
        GuideButtonTone.Success -> Button(
            onClick = onClick,
            enabled = false,
            modifier = buttonModifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(Icons.Filled.DoneAll, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(text, fontWeight = FontWeight.Bold)
        }
        GuideButtonTone.Neutral -> OutlinedButton(onClick = onClick, enabled = enabled && !loading, modifier = buttonModifier) { content() }
    }
}
