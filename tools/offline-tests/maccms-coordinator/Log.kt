package android.util

import java.util.concurrent.CopyOnWriteArrayList

object Log {
    val warnings = CopyOnWriteArrayList<String>()
    fun i(tag: String, message: String) = 0
    fun w(tag: String, message: String, failure: Throwable? = null): Int {
        warnings += "$tag: $message${failure?.message?.let { ": $it" }.orEmpty()}"
        return 0
    }
}
