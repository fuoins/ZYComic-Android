package com.zycomic.app.data.dto

data class Manga(
    val pic: String = "",
    val picx: String = "",
    val loveList: List<Manga> = emptyList(),
)
