package com.zycomic.app.ui.browse

import com.zycomic.app.reader.JmSource

/** Only public /promote data is cached here; never Manwa state or account-specific liked flags. */
internal object JmRandomAlbumRepository {
    private val cache = JmPromoteCache<List<JmSource.PromoteSection>>()
    private var previousId: String? = null

    suspend fun sections(forceRefresh: Boolean = false): List<JmSource.PromoteSection> =
        cache.get(forceRefresh) { JmSource.getPromoteSections() }

    suspend fun randomRoute(): String? {
        val pool = sections().firstOrNull { it.id == JmRandomAlbumPolicy.SERIES_UPDATE_SECTION_ID }
            ?.items.orEmpty().map { it.id }
        val id = JmRandomAlbumPolicy.choose(pool, previousId) ?: return null
        previousId = id
        return JmAlbumJumpPolicy.route(id)
    }
}
