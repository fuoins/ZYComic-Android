package android.util

object Base64 {
    const val URL_SAFE = 8
    const val NO_WRAP = 2
    const val NO_PADDING = 1

    fun encodeToString(input: ByteArray, flags: Int): String =
        java.util.Base64.getUrlEncoder().let { encoder ->
            (if (flags and NO_PADDING != 0) encoder.withoutPadding() else encoder).encodeToString(input)
        }
}
