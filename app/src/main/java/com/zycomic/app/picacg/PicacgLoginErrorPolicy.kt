package com.zycomic.app.picacg

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Locale

/** Pure policy for safely translating Picacg sign-in failures into user-facing Chinese messages. */
internal object PicacgLoginErrorPolicy {
    private val responseFailurePattern = Regex("^Picacg request failed \\((\\d{3})/([^)]*)\\):(?:\\s(.*))?$")

    fun classify(error: Throwable): PicacgLoginFailure {
        // Transport timeout types take precedence over all message parsing: timeout text must never
        // be mistaken for an API rate-limit response.
        if (error.causes().any { it is SocketTimeoutException || it is InterruptedIOException }) {
            return PicacgLoginFailure.CONNECTION_TIMEOUT
        }

        val response = error.causes().filterIsInstance<PicacgResponseException>().firstOrNull()
        val responseFailure = responseFailurePattern.matchEntire(error.message.orEmpty())
        val httpStatus = response?.httpStatus ?: responseFailure?.groupValues?.get(1)?.toIntOrNull()
        val serviceErrorCode = response?.serviceError ?: responseFailure?.groupValues?.get(2).orEmpty()
        if (httpStatus == 429 || response?.businessCode == 1023 || serviceErrorCode == "1023") return PicacgLoginFailure.RATE_LIMITED
        if (httpStatus == 401 || serviceErrorCode == "1005") return PicacgLoginFailure.UNAUTHORIZED

        val responseMessage = (response?.responseMessage ?: responseFailure?.groupValues?.get(3).orEmpty()).lowercase(Locale.ROOT)
        return when {
            "banned" in responseMessage -> PicacgLoginFailure.BANNED
            "locked" in responseMessage -> PicacgLoginFailure.LOCKED
            "invalid email" in responseMessage || "password" in responseMessage -> PicacgLoginFailure.INVALID_CREDENTIALS
            else -> PicacgLoginFailure.UNKNOWN
        }
    }

    fun message(failure: PicacgLoginFailure): String = when (failure) {
        PicacgLoginFailure.INVALID_CREDENTIALS -> "邮箱或密码错误。"
        PicacgLoginFailure.RATE_LIMITED -> "请求过于频繁，请稍后再试。"
        PicacgLoginFailure.BANNED -> "账号已被封禁。"
        PicacgLoginFailure.LOCKED -> "账号已锁定。"
        PicacgLoginFailure.UNAUTHORIZED -> "授权失败，请重新登录。"
        PicacgLoginFailure.CONNECTION_TIMEOUT -> "网络连接超时，请检查网络连接后重试。"
        PicacgLoginFailure.UNKNOWN -> "登录失败，请检查网络或服务器状态。"
    }

    private fun Throwable.causes(): Sequence<Throwable> = sequence {
        var current: Throwable? = this@causes
        while (current != null) {
            yield(current)
            current = current.cause
        }
    }
}
