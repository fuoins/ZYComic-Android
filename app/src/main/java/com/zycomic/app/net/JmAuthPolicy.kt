package com.zycomic.app.net

/** JWT is opt-in per JM operation and only sent to the configured API hosts. */
object JmAuthPolicy {
    fun mayAttachJwt(path: String, method: String, host: String, trustedApiHosts: Collection<String>): Boolean {
        if (path.startsWith("/media/") || path == "/login") return false
        if (trustedApiHosts.none { it.equals(host, ignoreCase = true) }) return false
        return when {
            method.equals("POST", true) -> path in setOf("/favorite", "/like", "/comment")
            method.equals("GET", true) -> path == "/album" || path == "/chapter"
            else -> false
        }
    }

    fun isLogin(path: String): Boolean = path == "/login"
    fun isImage(path: String): Boolean = path.startsWith("/media/")
}
