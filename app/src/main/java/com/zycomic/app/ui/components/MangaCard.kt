package com.zycomic.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.net.JmRouteManager
import com.zycomic.app.net.RouteManager
import java.net.URI

/**
 * 把漫画封面字段拼成可加载 URL。
 * picx/pic 可能是完整 URL，也可能是图源相对路径。
 */
fun coverUrl(manga: Manga): String {
    val raw = manga.picx.ifBlank { manga.pic }
    return coverUrl(raw)
}

/**
 * 把封面原始字段拼成可加载 URL（book_img / picx / pic 通用）。
 * 可能是完整 URL，也可能是图源相对路径；相对路径用 [RouteManager.imgHost] 补全。
 * 封面请求会走 Coil 共享的 OkHttpClient（含 ImageInterceptor 解密）。
 */
fun coverUrl(raw: String): String {
    if (raw.isBlank()) return ""
    // 统一读 imgRewriteTarget：手动 pin 优先，其次才是开关开启时的派生最快域
    val target = RouteManager.imgRewriteTarget
    if (raw.startsWith("http://") || raw.startsWith("https://")) {
        val rawHost = runCatching { URI(raw).host.orEmpty() }.getOrDefault("")
        if (rawHost.endsWith(".jmapinodeudzn.net", ignoreCase = true) || JmRouteManager.imageDomains.any { it.equals(rawHost, ignoreCase = true) }) {
            return raw
        }
        if (!target.isNullOrBlank()) {
            return raw.replace(Regex("https?://[^/]+"), "https://$target")
        }
        return raw
    }
    val host = target ?: RouteManager.imgHost
    val sep = if (raw.startsWith("/")) "" else "/"
    return "https://$host$sep$raw"
}

/**
 * 漫画卡片：统一走 [GlassMangaGridCard]（与收藏页/分类页/搜索页同一版式）。
 */
@Composable
fun MangaCard(
    manga: Manga,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    displayMode: Int = 1,
) {
    val url = coverUrl(manga)
    GlassMangaGridCard(
        title = manga.name,
        displayMode = displayMode,
        onClick = onClick,
        modifier = modifier,
        cover = {
            if (url.isNotEmpty()) {
                SubcomposeAsyncImage(
                    model = url,
                    contentDescription = manga.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    error = { com.zycomic.app.ui.components.CoverError(Modifier.fillMaxSize()) },
                )
            }
        },
    )
}
