package com.zycomic.app.reader

/** Small caller-side limit for Picacg reader look-ahead; this does not alter image request routing. */
internal object PicacgReaderPreloadPolicy {
    const val MAX_PRELOADED_PAGES = 3

    fun taskLimit(configuredSize: Int, isPicacg: Boolean, readerThreadsEnabled: Boolean): Int {
        if (!readerThreadsEnabled) return 0
        val max = if (isPicacg) MAX_PRELOADED_PAGES else 10
        return configuredSize.coerceIn(0, max)
    }
}
