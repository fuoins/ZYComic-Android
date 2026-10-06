package com.zycomic.app.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** 只做图片host替换的轻量拦截器，供Coil的NetworkHelper.client使用。 */
class FastestImgInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val host = RouteManager.fastestImgDomain
        if (RouteManager.useFastestImgForAll && !host.isNullOrBlank() &&
            (req.url.toString().contains("/static/upload") || req.url.toString().contains(".webp") ||
                req.url.toString().contains(".jpg") || req.url.toString().contains(".png"))
        ) {
            val newUrl = req.url.toString().replace(Regex("https?://[^/]+"), "https://$host")
            return chain.proceed(req.newBuilder().url(newUrl.toHttpUrl()).build())
        }
        return chain.proceed(req)
    }
}
