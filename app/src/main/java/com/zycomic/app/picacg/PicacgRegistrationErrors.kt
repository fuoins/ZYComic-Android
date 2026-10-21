package com.zycomic.app.picacg

import java.io.IOException

class PicacgRegistrationException(message: String) : IOException(message)

/** Maps only stable protocol codes; server response bodies are never copied into the UI. */
internal object PicacgRegistrationErrorPolicy {
    fun message(error: Throwable): String {
        val response = error.causes().filterIsInstance<PicacgResponseException>().firstOrNull()
        return when {
            response?.businessCode == 1023 || response?.serviceError == "1023" || response?.httpStatus == 429 ->
                "请求过于频繁，请稍后再试。"
            response?.businessCode == 1025 || response?.businessCode == 1026 ||
                response?.serviceError in setOf("1025", "1026") ->
                "账号或昵称已被占用，请更换后重试。"
            else -> "注册失败，请检查网络或稍后重试。"
        }
    }

    private fun Throwable.causes(): Sequence<Throwable> = sequence {
        var current: Throwable? = this@causes
        while (current != null) {
            yield(current)
            current = current.cause
        }
    }
}
