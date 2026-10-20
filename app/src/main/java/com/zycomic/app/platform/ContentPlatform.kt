package com.zycomic.app.platform

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/** UI 平台选择；Picacg 与 JM 保持独立身份，接口未接入时不得回退到其他平台数据。 */
enum class ContentPlatform(val id: String, val accent: Color) {
    MANWA("manwa", Color(0xFF248A48)),
    PICACG("picacg", Color(0xFFFF77A8)),
    JM("jm", Color(0xFFEF8733)),
    ;

    companion object {
        fun fromId(id: String?): ContentPlatform = entries.firstOrNull { it.id == id } ?: JM
    }
}

/** 页面平台偏好各自保存，互不影响，并在应用重启后恢复。 */
internal object ContentPlatformPrefs {
    private const val PREFS = "zycomic_content_platforms"
    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun load(page: String): ContentPlatform {
        val remembered = ContentPlatform.fromId(prefs.getString(page, null))
        val resolved = StartupPlatformPolicy.resolve(remembered)
        if (resolved != remembered) prefs.edit().putString(page, resolved.id).apply()
        return resolved
    }

    fun save(page: String, platform: ContentPlatform) {
        prefs.edit().putString(page, platform.id).apply()
    }
}

/** 可在外观设置中自定义的平台强调色，存储为 ARGB 十六进制并即时通知界面。 */
object ContentPlatformAccentPrefs {
    private const val PREFS = "zycomic_content_platform_accents"
    private const val KEY_MANWA = "manwa"
    private const val KEY_PICACG = "picacg"
    private const val KEY_JM = "jm"
    private lateinit var prefs: android.content.SharedPreferences

    private val _manwa = MutableStateFlow(ContentPlatform.MANWA.accent)
    private val _picacg = MutableStateFlow(ContentPlatform.PICACG.accent)
    private val _jm = MutableStateFlow(ContentPlatform.JM.accent)
    val manwa: StateFlow<Color> = _manwa
    val picacg: StateFlow<Color> = _picacg
    val jm: StateFlow<Color> = _jm

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _manwa.value = prefs.getString(KEY_MANWA, null)?.let(::parseColor)
            ?: ContentPlatform.MANWA.accent
        _picacg.value = prefs.getString(KEY_PICACG, null)?.let(::parseColor)
            ?: ContentPlatform.PICACG.accent
        _jm.value = prefs.getString(KEY_JM, null)?.let(::parseColor)
            ?: ContentPlatform.JM.accent
    }

    fun color(platform: ContentPlatform): Color = when (platform) {
        ContentPlatform.MANWA -> _manwa.value
        ContentPlatform.PICACG -> _picacg.value
        ContentPlatform.JM -> _jm.value
    }

    fun set(platform: ContentPlatform, color: Color) {
        val key = key(platform)
        val hex = color.toArgb().toUInt().toString(16).uppercase().padStart(8, '0')
        prefs.edit().putString(key, hex).apply()
        setState(platform, color)
    }

    fun reset(platform: ContentPlatform) {
        val key = key(platform)
        prefs.edit().remove(key).apply()
        setState(platform, platform.accent)
    }

    fun format(color: Color): String {
        val hex = color.toArgb().toUInt().toString(16).uppercase().padStart(8, '0')
        return "#$hex"
    }

    fun parseColor(value: String): Color? {
        val hex = value.trim().removePrefix("#")
        val normalized = when (hex.length) {
            6 -> "FF$hex"
            8 -> hex
            else -> return null
        }
        return normalized.toLongOrNull(16)?.toInt()?.let { Color(it) }
    }

    private fun setState(platform: ContentPlatform, color: Color) {
        when (platform) {
            ContentPlatform.MANWA -> _manwa.value = color
            ContentPlatform.PICACG -> _picacg.value = color
            ContentPlatform.JM -> _jm.value = color
        }
    }

    private fun key(platform: ContentPlatform): String = when (platform) {
        ContentPlatform.MANWA -> KEY_MANWA
        ContentPlatform.PICACG -> KEY_PICACG
        ContentPlatform.JM -> KEY_JM
    }
}

@Composable
fun rememberContentPlatformAccent(platform: ContentPlatform): Color {
    val color by when (platform) {
        ContentPlatform.MANWA -> ContentPlatformAccentPrefs.manwa
        ContentPlatform.PICACG -> ContentPlatformAccentPrefs.picacg
        ContentPlatform.JM -> ContentPlatformAccentPrefs.jm
    }.collectAsState()
    return color
}

/** 胶囊式平台切换器；compact 用于工具栏，large 用于个人页。 */
@Composable
fun ContentPlatformSelector(
    selected: ContentPlatform,
    onSelect: (ContentPlatform) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    compactButtonSize: androidx.compose.ui.unit.Dp? = null,
    compactJmLabel: String? = null,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (compact) 0.75f else 0.55f),
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(3.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContentPlatform.entries.forEach { platform ->
                val isSelected = selected == platform
                val accent = rememberContentPlatformAccent(platform)
                Box(
                    modifier = Modifier
                        .then(if (compact) Modifier else Modifier.weight(1f))
                        .then(
                            if (compact && compactButtonSize != null) {
                                Modifier.size(compactButtonSize)
                            } else {
                                Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .widthIn(min = if (compact) 48.dp else 0.dp)
                            },
                        )
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelect(platform) },
                        )
                        .clip(RoundedCornerShape(50))
                        .background(if (isSelected) accent else Color.Transparent)
                        .padding(horizontal = if (compact) 4.dp else 10.dp, vertical = if (compact) 5.dp else 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (compact && platform == ContentPlatform.JM && compactJmLabel != null) {
                            compactJmLabel
                        } else {
                            stringResource(
                                when (platform) {
                                    ContentPlatform.MANWA -> KMR.strings.content_platform_manwa
                                    ContentPlatform.PICACG -> KMR.strings.content_platform_picacg
                                    ContentPlatform.JM -> KMR.strings.content_platform_jm
                                },
                            )
                        },
                        color = if (isSelected) {
                            if (accent.luminance() > 0.55f) Color.Black else Color.White
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = if (compact) 10.sp else 15.sp,
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
    platform: ContentPlatform = ContentPlatform.JM,
    detail: String,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(
                when (platform) {
                    ContentPlatform.MANWA -> KMR.strings.content_platform_manwa
                    ContentPlatform.PICACG -> KMR.strings.content_platform_picacg
                    ContentPlatform.JM -> KMR.strings.content_platform_jm
                },
            ),
            color = rememberContentPlatformAccent(platform),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(KMR.strings.content_platform_interface_pending),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            detail,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 320.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
