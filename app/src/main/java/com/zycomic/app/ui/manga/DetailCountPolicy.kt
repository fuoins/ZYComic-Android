package com.zycomic.app.ui.manga

/** Preserve every decimal digit; unknown counts are not displayed as zero. */
internal fun jmCountForDisplay(count: Int?): String = count?.toString() ?: "—"
