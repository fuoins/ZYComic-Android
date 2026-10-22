package com.zycomic.app.data.dto
data class Chapter(val id:String="",val name:String="",val sort:Int=0)
data class TagItem(val name:String="")
data class Manga(val id:String="",val name:String="",val author:String="",val picx:String="",val text:String="",val state:String="",val end:String="",val hits:Int=0,val nums:Int=0,val commentNums:Int=0,val fav:Int=0,val tags:List<TagItem> = emptyList(),val chapterList:List<Chapter> = emptyList())
