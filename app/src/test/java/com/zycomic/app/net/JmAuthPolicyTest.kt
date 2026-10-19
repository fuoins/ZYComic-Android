package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmAuthPolicyTest {
    @Test
    fun `only protected user write paths receive JWT`() {
        assertTrue(JmAuthPolicy.mayAttachJwt("/favorite", "POST", "api.test", listOf("api.test")))
        assertTrue(JmAuthPolicy.mayAttachJwt("/like", "POST", "api.test", listOf("api.test")))
        assertTrue(JmAuthPolicy.mayAttachJwt("/comment", "POST", "api.test", listOf("api.test")))
        assertTrue(JmAuthPolicy.mayAttachJwt("/comment_delete", "POST", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/comment_delete", "GET", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/comment_delete/", "POST", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/comment_delete", "POST", "redirect.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/login", "POST", "api.test", listOf("api.test")))
        assertTrue(JmAuthPolicy.mayAttachJwt("/album", "GET", "api.test", listOf("api.test")))
        assertTrue(JmAuthPolicy.mayAttachJwt("/favorite", "GET", "api.test", listOf("api.test")))
        assertTrue(JmAuthPolicy.mayAttachJwt("/watch_list", "GET", "api.test", listOf("api.test")))
        assertTrue(JmAuthPolicy.mayAttachJwt("/forum", "GET", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/forum", "POST", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/forum/", "GET", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/forum", "GET", "redirect.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/media/forum", "GET", "api.test", listOf("api.test")))
        assertTrue(JmAuthPolicy.mayAttachJwt("/watch_list", "POST", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/favorite", "GET", "redirect.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/watch_list/", "GET", "api.test", listOf("api.test")))
        assertTrue(JmAuthPolicy.mayAttachJwt("/chapter", "GET", "api.test", listOf("api.test")))
        assertTrue(JmAuthPolicy.mayAttachJwt("/useredit/12345678", "GET", "api.test", listOf("api.test")))
        assertTrue(JmAuthPolicy.mayAttachJwt("/useredit/12345678", "POST", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/media/users/a.jpg", "GET", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/register", "POST", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/forgot", "POST", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/confirm", "POST", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/useredit/not-a-uid", "POST", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/favorite", "POST", "redirect.test", listOf("api.test")))
        assertTrue(JmAuthPolicy.mayAttachJwt("/daily", "GET", "api.test", listOf("api.test")))
        assertTrue(JmAuthPolicy.mayAttachJwt("/daily_chk", "POST", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/daily", "POST", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/daily_chk", "GET", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/daily/", "GET", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/daily_chk/extra", "POST", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/daily", "GET", "redirect.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/daily_chk", "POST", "redirect.test", listOf("api.test")))
    }

    @Test
    fun `folder and history mutations authenticate only exact trusted API paths`() {
        listOf("/favorite_folder", "/watch_list").forEach { path ->
            assertTrue(JmAuthPolicy.mayAttachJwt(path, "POST", "api.test", listOf("api.test")))
            assertFalse(JmAuthPolicy.mayAttachJwt(path, "POST", "redirect.test", listOf("api.test")))
            assertFalse(JmAuthPolicy.mayAttachJwt("$path/", "POST", "api.test", listOf("api.test")))
            assertFalse(JmAuthPolicy.mayAttachJwt(path, "DELETE", "api.test", listOf("api.test")))
        }
        assertFalse(JmAuthPolicy.mayAttachJwt("/favorite_folder", "GET", "api.test", listOf("api.test")))
    }

    @Test
    fun `login is explicitly unauthenticated and images are recognized`() {
        assertTrue(JmAuthPolicy.isLogin("/login"))
        assertFalse(JmAuthPolicy.isLogin("/favorite"))
        assertTrue(JmAuthPolicy.isImage("/media/photos/1/a.jpg"))
    }
}
