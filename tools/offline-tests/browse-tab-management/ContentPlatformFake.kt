package com.zycomic.app.platform

enum class ContentPlatform(val id: String) {
    MANWA("manwa"),
    PICACG("picacg"),
    JM("jm");

    companion object {
        fun fromId(id: String?): ContentPlatform = entries.firstOrNull { it.id == id } ?: JM
    }
}
