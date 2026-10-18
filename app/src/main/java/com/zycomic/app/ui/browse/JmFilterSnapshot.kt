package com.zycomic.app.ui.browse

import org.json.JSONObject

/** JSON representation for a JM tab's independent filters; deliberately separate from Manwa FilterSnapshot. */
internal data class JmFilterSnapshot(
    val mode: Int = 0,
    val query: String = "",
    val order: String = "",
    val category: String = "",
    val weekId: String = "",
    val weekType: String = "manga",
    val serializationType: String = "all",
    val serializationDate: Int = JmBrowsePolicy.today(),
    val promoteId: String = "",
    val promoteTitle: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("query", query)
        put("order", order)
        put("category", category)
        put("weekId", weekId)
        put("weekType", weekType)
        put("serializationType", serializationType)
        put("serializationDate", serializationDate)
        put("promoteId", promoteId)
        put("promoteTitle", promoteTitle)
    }

    fun toFilters(): JmBrowsePolicy.Filters = JmBrowsePolicy.Filters(
        mode = mode,
        query = query,
        order = order,
        category = category,
        weekId = weekId,
        weekType = weekType,
        serializationType = serializationType,
        serializationDate = serializationDate,
        promoteId = promoteId,
        promoteTitle = promoteTitle,
    )

    companion object {
        fun fromFilters(filters: JmBrowsePolicy.Filters): JmFilterSnapshot = JmFilterSnapshot(
            mode = filters.mode,
            query = filters.query,
            order = filters.order,
            category = filters.category,
            weekId = filters.weekId,
            weekType = filters.weekType,
            serializationType = filters.serializationType,
            serializationDate = filters.serializationDate,
            promoteId = filters.promoteId,
            promoteTitle = filters.promoteTitle,
        )

        fun fromJson(json: JSONObject, defaultMode: Int = 0): JmFilterSnapshot = JmFilterSnapshot(
            mode = json.optInt("mode", defaultMode),
            query = json.optString("query"),
            order = json.optString("order"),
            category = json.optString("category"),
            weekId = json.optString("weekId"),
            weekType = json.optString("weekType").ifBlank { "manga" },
            serializationType = json.optString("serializationType").ifBlank { "all" },
            serializationDate = json.optInt("serializationDate", JmBrowsePolicy.today()),
            promoteId = json.optString("promoteId"),
            promoteTitle = json.optString("promoteTitle"),
        )
    }
}
