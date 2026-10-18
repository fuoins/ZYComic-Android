package com.zycomic.app.ui.browse

/** Pure validation and route construction for manually entered JM album IDs. */
internal object JmAlbumJumpPolicy {
    fun albumId(input: String): String? {
        val value = input.trim()
        if (!value.matches(Regex("[0-9]+"))) return null
        val number = value.toBigIntegerOrNull() ?: return null
        if (number.signum() <= 0) return null
        return number.toString()
    }

    fun route(input: String): String? = albumId(input)?.let { "jm:$it" }
}
