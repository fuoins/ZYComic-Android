package com.zycomic.app.ui.components

import android.content.Context
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.net.PicacgImageFallbackInterceptor
import com.zycomic.app.net.PicacgRouteManager
import com.zycomic.app.net.PicacgRuntimeImageInterceptor
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** A Picacg-only client; never uses the shared Manwa image interceptor or platform cookies. */
object PicacgImageLoader {
    @Volatile private var instance: ImageLoader? = null

    /** Picacg disk cache stays in its own directory, separate from Manwa and JM. */
    fun diskCacheDirectory(context: Context): File =
        context.applicationContext.cacheDir.resolve("picacg_image_cache")

    fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: ImageLoader.Builder(context.applicationContext)
            .components { add(OkHttpNetworkFetcherFactory(newHttpClient())) }
            .diskCache {
                DiskCache.Builder()
                    .directory(diskCacheDirectory(context))
                    .maxSizePercent(0.04)
                    .build()
            }
            .build()
            .also { instance = it }
    }

    /** Clear only Picacg's memory and disk caches. */
    fun clearCache(context: Context) {
        val loader = get(context)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

    private fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .dns(PicacgRouteManager.dns())
        .addInterceptor(
            Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .url(PicacgRouteManager.rewriteImageUrl(chain.request().url.toString()))
                    .header("User-Agent", "okhttp/3.8.1")
                    .header("Referer", "https://www.picacomic.com/")
                    .build()
                chain.proceed(request)
            },
        )
        .addInterceptor(PicacgRuntimeImageInterceptor())
        .addInterceptor(PicacgImageFallbackInterceptor())
        .build()
}

/**
 * Manga tile for Picacg results. `picx` is passed through as provided by the Picacg repository;
 * do not send it through [coverUrl], which rewrites non-JM hosts using Manwa routing.
 */
@Composable
fun PicacgMangaCard(
    manga: Manga,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rank: Int? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val rawPicacgCoverUrl = manga.picx.ifBlank { manga.pic }
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (rawPicacgCoverUrl.isNotBlank()) {
                SubcomposeAsyncImage(
                    model = rawPicacgCoverUrl,
                    contentDescription = manga.name,
                    imageLoader = PicacgImageLoader.get(context),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    error = { CoverError(Modifier.fillMaxSize()) },
                )
            }
            rank?.let { PicacgRankBadge(rank = it, modifier = Modifier.align(Alignment.TopStart)) }
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
