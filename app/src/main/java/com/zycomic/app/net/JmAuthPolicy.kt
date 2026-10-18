package com.zycomic.app.net

/** JWT is opt-in per JM operation and only sent to the configured API hosts. */
object JmAuthPolicy {
    fun mayAttachJwt(path: String, method: String, host: String, trustedApiHosts: Collection<String>): Boolean {
        if (path.startsWith("/media/") || path == "/login") return false
        if (trustedApiHosts.none { it.equals(host, ignoreCase = true) }) return false
        return when {
            method.equals("POST", true) -> isUserEdit(path) || path in setOf("/favorite", "/favorite_folder", "/watch_list", "/like", "/comment", "/daily_chk")
            method.equals("GET", true) -> path == "/album" || path == "/chapter" || path == "/daily" || path in USER_LIST_GET_PATHS || isUserEdit(path)
            else -> false
        }
    }

    fun isLogin(path: String): Boolean = path == "/login"
    fun isImage(path: String): Boolean = path.startsWith("/media/")

    private fun isUserEdit(path: String): Boolean = USER_EDIT_PATH.matches(path)

    private val USER_EDIT_PATH = Regex("/useredit/[0-9]+")
    private val USER_LIST_GET_PATHS = setOf("/favorite", "/watch_list")
}
