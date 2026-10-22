package com.zycomic.app.picacg

/** Isolates the anonymous external ID transport; production metadata loading/filtering stays real. */
internal object MaccmsRecommendationRouter {
    var ids: List<String> = emptyList()

    suspend fun recommendationIds(comicId: String): List<String> = ids
}
