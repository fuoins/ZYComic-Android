package tachiyomi.core.common.util.system

fun logcat(message: () -> String) {
    message()
}
