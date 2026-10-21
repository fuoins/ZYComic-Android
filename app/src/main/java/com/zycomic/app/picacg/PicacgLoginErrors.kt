package com.zycomic.app.picacg

enum class PicacgLoginFailure { INVALID_CREDENTIALS, RATE_LIMITED, BANNED, LOCKED, UNAUTHORIZED, CONNECTION_TIMEOUT, UNKNOWN }

class PicacgLoginException(val reason: PicacgLoginFailure, message: String) : java.io.IOException(message)

internal class PicacgResponseException(
    val httpStatus: Int,
    val businessCode: Int,
    val serviceError: String,
    val responseMessage: String,
) : java.io.IOException("Picacg request failed ($httpStatus/$serviceError): $responseMessage")
