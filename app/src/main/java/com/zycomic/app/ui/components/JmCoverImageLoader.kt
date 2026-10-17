package com.zycomic.app.ui.components

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.zycomic.app.net.JmNetworkModule

/** JM 封面使用 JM 自己的 image client，绕过应用全局 Manwa ImageInterceptor。 */
object JmCoverImageLoader {
    @Volatile private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: ImageLoader.Builder(context.applicationContext)
            .components { add(OkHttpNetworkFetcherFactory { JmNetworkModule.client }) }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("jm_cover_cache"))
                    .maxSizePercent(0.04)
                    .build()
            }
            .build()
            .also { instance = it }
    }
}
