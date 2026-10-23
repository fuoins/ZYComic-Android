package com.zycomic.app.picacg

data class PicacgCategory(
    val id: String,
    val title: String,
    val description: String = "",
    val active: Boolean = true,
    val isWeb: Boolean = false,
)
