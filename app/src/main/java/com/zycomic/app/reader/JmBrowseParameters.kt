package com.zycomic.app.reader

/** Exact query contracts for JM list endpoints that do not accept the category order parameter. */
internal object JmBrowseParameters {
    fun weekFilter(id: String, type: String, page: Int): Map<String, String> = mapOf(
        "id" to id,
        "type" to type,
        "page" to page.coerceAtLeast(1).toString(),
    )

    fun serialization(type: String, date: Int, page: Int): Map<String, String> = mapOf(
        "type" to type,
        "date" to date.toString(),
        "page" to page.coerceAtLeast(1).toString(),
    )
}
