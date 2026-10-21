package com.zycomic.app.ui.picacg

/** Search routes triggered from a Picacg detail page never inherit the remembered provider. */
object PicacgDetailSearchPolicy {
    const val SEARCH_PROVIDER = "picacg"

    enum class Source {
        TITLE,
        AUTHOR,
        TAG,
    }

    fun query(source: Source, value: String): String? = when (source) {
        Source.TITLE, Source.AUTHOR, Source.TAG -> value.trim().takeIf(String::isNotEmpty)
    }
}
