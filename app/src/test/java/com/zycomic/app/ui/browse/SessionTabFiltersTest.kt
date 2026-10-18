package com.zycomic.app.ui.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionTabFiltersTest {
    @Test
    fun `switching tabs keeps temporary filters without changing defaults`() {
        val defaults = mutableMapOf("manwa:one" to "default-one", "jm:one" to "default-jm")
        val session = SessionTabFilters<String>()
        assertEquals("default-one", session.get("manwa:one") { defaults.getValue("manwa:one") })
        session.set("manwa:one", "temporary")
        assertEquals("default-jm", session.get("jm:one") { defaults.getValue("jm:one") })
        assertEquals("temporary", session.get("manwa:one") { defaults.getValue("manwa:one") })
        assertEquals("default-one", defaults.getValue("manwa:one"))
        assertEquals("default-one", SessionTabFilters<String>().get("manwa:one") { defaults.getValue("manwa:one") })
    }

    @Test
    fun `removing and resetting tabs discard only the appropriate overrides`() {
        val session = SessionTabFilters<Int>()
        session.set("a", 2)
        session.set("b", 3)
        session.remove("a")
        assertEquals(1, session.get("a") { 1 })
        assertEquals(3, session.get("b") { 1 })
        session.clear()
        assertEquals(1, session.get("b") { 1 })
    }
}
