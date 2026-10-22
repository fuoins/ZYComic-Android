package com.zycomic.app.net
import okhttp3.Dns
object PicacgRouteManager {
    var apiBaseUrl = "https://api.example"
    var currentImageHost: String? = "img.example"
    var configRevision: Long = 0L
    fun dns(): Dns = Dns.SYSTEM
    fun setVerifiedImageSampleUrlFromApiResponse(url: String) {}
    fun rewriteImageUrl(url: String): String = url
}
