package com.zycomic.app.ui.manga

internal fun detailAuthorsForDisplay(authors: List<String>, expanded: Boolean): List<String> =
    if (expanded) authors else authors.take(1)

internal fun detailTitleMaxLines(expanded: Boolean): Int = if (expanded) Int.MAX_VALUE else 3

internal fun shouldShowJmAuthorNote(isJm: Boolean, expanded: Boolean): Boolean = isJm && expanded
