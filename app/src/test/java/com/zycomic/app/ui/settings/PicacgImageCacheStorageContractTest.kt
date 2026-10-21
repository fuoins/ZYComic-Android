package com.zycomic.app.ui.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PicacgImageCacheStorageContractTest {
    private fun source(path: String): String =
        sequenceOf(File("app/src/main/java/$path"), File("src/main/java/$path"))
            .first { it.isFile }
            .readText()

    @Test
    fun `Picacg loader owns a stable cache directory and clears only its own loader`() {
        val loader = source("com/zycomic/app/ui/components/PicacgMangaCard.kt")
        assertTrue(loader.contains("cacheDir.resolve(\"picacg_image_cache\")"))
        assertTrue(loader.contains(".directory(diskCacheDirectory(context))"))
        assertTrue(loader.contains("fun clearCache(context: Context)"))
        assertTrue(loader.contains("loader.memoryCache?.clear()"))
        assertTrue(loader.contains("loader.diskCache?.clear()"))
    }

    @Test
    fun `storage total includes the Picacg disk cache without merging cache directories`() {
        val screen = source("com/zycomic/app/ui/settings/DataStorageScreen.kt")
        assertTrue(screen.contains("PicacgImageLoader.diskCacheDirectory(context)"))
        assertTrue(screen.contains("coilBytes + picacgBytes"))
        assertTrue(screen.contains("KMR.strings.picacg_image_cache_size"))
        assertTrue(screen.contains("remember(cacheRevision) { imageDiskCacheSize() }"))
        assertTrue(screen.contains("cacheRevision++"))
    }

    @Test
    fun `global image cleanup clears Coil and Picacg independently and leaves JM alone`() {
        val devConfig = source("com/zycomic/app/net/DevConfig.kt")
        val clearMethod = devConfig.substringAfter("fun clearImageCaches()")
            .substringBefore("fun clearImageMemoryCache()")
        assertTrue(clearMethod.contains("SingletonImageLoader.get(ctx)"))
        assertTrue(clearMethod.contains("PicacgImageLoader.clearCache(ctx)"))
        assertFalse(clearMethod.contains("JmCoverImageLoader"))
        assertFalse(clearMethod.contains("jm_cover_cache"))
    }
}
