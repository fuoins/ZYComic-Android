package com.zycomic.app.ui.manga

/** Related entries carry raw album IDs; keep navigation on their originating platform. */
internal fun relatedMangaRoute(id: String, isJm: Boolean): String = if (isJm) "jm:${id.removePrefix("jm:")}" else id
