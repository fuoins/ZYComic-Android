package com.zycomic.app.platform

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/** UI 平台选择；禁漫天堂在接口接入前仅展示占位，不映射到漫蛙数据。 */
enum class ContentPlatform(val id: String, val accent: Color) {
    MANWA("manwa", Color(0xFF248A48)),
    JM("jm", Color(0xFFEF8733)),
    ;

    companion object {
        fun fromId(id: String?): ContentPlatform = entries.firstOrNull { it.id == id } ?: MANWA
    }
}

/** 页面平台偏好各自保存，互不影响，并在应用重启后恢复。 */
internal object ContentPlatformPrefs {
    private const val PREFS = "zycomic_content_platforms"
    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun load(page: String): ContentPlatform = ContentPlatform.fromId(prefs.getString(page, null))

    fun save(page: String, platform: ContentPlatform) {
        prefs.edit().putString(page, platform.id).apply()
    }
}

/** 胶囊式平台切换器；compact 用于工具栏，large 用于个人页。 */
@Composable
fun ContentPlatformSelector(
    selected: ContentPlatform,
    onSelect: (ContentPlatform) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (compact) 0.75f else 0.55f),
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContentPlatform.entries.forEach { platform ->
                val isSelected = selected == platform
                Box(
                    modifier = Modifier
                        .then(if (compact) Modifier else Modifier.weight(1f))
                        .widthIn(min = if (compact) 44.dp else 112.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (isSelected) platform.accent else Color.Transparent)
                        .clickable { onSelect(platform) }
                        .padding(horizontal = if (compact) 7.dp else 18.dp, vertical = if (compact) 6.dp else 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            when (platform) {
                                ContentPlatform.MANWA -> KMR.strings.content_platform_manwa
                                ContentPlatform.JM -> KMR.strings.content_platform_jm
                            },
                        ),
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = if (compact) 11.sp else 15.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun PlatformNotConnectedContent(
    modifier: Modifier = Modifier,
    detail: String,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(KMR.strings.content_platform_jm), color = ContentPlatform.JM.accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(KMR.strings.content_platform_interface_pending), modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
        Text(
            detail,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 320.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
