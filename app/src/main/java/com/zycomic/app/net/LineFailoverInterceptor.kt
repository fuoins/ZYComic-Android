package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class LineFailoverInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return try {
            val resp = chain.proceed(chain.request())
            RouteManager.recordApiSuccess()
            resp
        } catch (e: IOException) {
            RouteManager.recordApiFailure()
            throw e
        }
    }
}
