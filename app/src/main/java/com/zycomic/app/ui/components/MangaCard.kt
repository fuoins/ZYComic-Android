package com.zycomic.app.ui.components

import coil3.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
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
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.net.RouteManager

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
    if (raw.startsWith("http://") || raw.startsWith("https://")) {
        if (RouteManager.useFastestImgForAll && !RouteManager.fastestImgDomain.isNullOrBlank()) {
            return raw.replace(Regex("https?://[^/]+"), "https://${RouteManager.fastestImgDomain}")
        }
        return raw
    }
    val host = if (RouteManager.useFastestImgForAll) RouteManager.fastestImgDomain ?: RouteManager.imgHost else RouteManager.imgHost
    val sep = if (raw.startsWith("/")) "" else "/"
    return "https://$host$sep$raw"
}

/**
 * 漫画卡片：封面图（Coil，圆角 8dp）+ 标题（最多 2 行 ellipsis）。
 */
@Composable
fun MangaCard(
    manga: Manga,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val url = coverUrl(manga)
            if (url.isNotEmpty()) {
                AsyncImage(
                    model = url,
                    contentDescription = manga.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Text(
            text = manga.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}
