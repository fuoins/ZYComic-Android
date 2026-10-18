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
        assertFalse(JmAuthPolicy.mayAttachJwt("/login", "POST", "api.test", listOf("api.test")))
        assertTrue(JmAuthPolicy.mayAttachJwt("/album", "GET", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/forum", "GET", "api.test", listOf("api.test")))
        assertTrue(JmAuthPolicy.mayAttachJwt("/chapter", "GET", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/media/users/a.jpg", "GET", "api.test", listOf("api.test")))
        assertFalse(JmAuthPolicy.mayAttachJwt("/favorite", "POST", "redirect.test", listOf("api.test")))
    }

    @Test
    fun `login is explicitly unauthenticated and images are recognized`() {
        assertTrue(JmAuthPolicy.isLogin("/login"))
        assertFalse(JmAuthPolicy.isLogin("/favorite"))
        assertTrue(JmAuthPolicy.isImage("/media/photos/1/a.jpg"))
    }
}
