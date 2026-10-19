package com.zycomic.app.ui.manga

/** Visual distinction for the JM like button independent of Material theme colors. */
internal data class JmLikeVisualState(val liked: Boolean, val enabled: Boolean)

internal fun jmLikeVisualState(liked: Boolean, enabled: Boolean): JmLikeVisualState =
    JmLikeVisualState(liked = liked, enabled = enabled)
