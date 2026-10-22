package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PicacgBuiltinNetworkTest {
    @Test
    fun `fixed routes and selectable image hosts have stable order`() {
        assertEquals(
            listOf("https://picaapi.go2778.com/", "https://picaapi.picacomic.com/"),
            PicacgBuiltinNetwork.apiUrls,
        )
        assertEquals(
            listOf("storage-b.safedataplj.com", "storage-b.go2778.com", "storage-b.picacomic.com"),
            PicacgBuiltinNetwork.imageHosts,
        )
        assertEquals(
            listOf("img.safedataplj.com", "img.go2778.com", "img.picacomic.com"),
            PicacgBuiltinNetwork.coverHosts,
        )
    }

    @Test
    fun `content and cover mirrors map within the same root and tile url is auth free`() {
        assertEquals("img.safedataplj.com", PicacgBuiltinNetwork.coverHostFor("storage-b.safedataplj.com"))
        assertEquals("img.go2778.com", PicacgBuiltinNetwork.coverHostFor("storage-b.go2778.com"))
        assertEquals("img.picacomic.com", PicacgBuiltinNetwork.coverHostFor("storage-b.picacomic.com"))
        assertEquals("storage-b.safedataplj.com", PicacgBuiltinNetwork.contentHostFor("img.safedataplj.com"))
        assertTrue(PicacgBuiltinNetwork.isCoverHost("img.go2778.com"))
        assertEquals(null, PicacgBuiltinNetwork.coverHostFor("picaapi.picacomic.com"))
        assertEquals(null, PicacgBuiltinNetwork.coverHostFor("storage1.picacomic.com"))
        assertEquals(null, PicacgBuiltinNetwork.contentHostFor("storage-b.evil.example"))
        val tile = PicacgBuiltinNetwork.speedTileUrl("img.safedataplj.com")
        assertTrue(tile.startsWith("https://img.safedataplj.com/insecure/rs:fill:50:67:0/"))
        assertEquals(listOf("104.21.75.42", "172.67.213.2"), PicacgBuiltinNetwork.builtInLookup("img.safedataplj.com"))
    }

    @Test
    fun `builtin rules contain prescribed roots and exact service roles`() {
        val rules = PicacgBuiltinNetwork.rules()
        assertEquals(listOf("104.21.42.75", "172.67.159.59"), rules["go2778.com"])
        assertEquals(listOf("104.20.42.9", "172.66.173.71"), rules["picacomic.com"])
        assertEquals(listOf("104.21.75.42", "172.67.213.2"), rules["safedataplj.com"])
        assertEquals(rules["go2778.com"], rules["picaapi.go2778.com"])
        assertEquals(rules["picacomic.com"], rules["storage-b.picacomic.com"])
        assertEquals(rules["safedataplj.com"], rules["img.safedataplj.com"])
    }

    @Test
    fun `lookup matches roots only on label boundaries and prefers exact entries`() {
        val rules = mapOf(
            "picacomic.com" to listOf("104.20.42.9"),
            "picaapi.picacomic.com" to listOf("1.1.1.1"),
        )
        assertEquals(listOf("1.1.1.1"), PicacgBuiltinNetwork.lookup(rules, "PICAAPI.PICACOMIC.COM."))
        assertEquals(listOf("104.20.42.9"), PicacgBuiltinNetwork.lookup(rules, "deep.sub.picacomic.com"))
        assertTrue(PicacgBuiltinNetwork.lookup(rules, "notpicacomic.com").isEmpty())
        assertTrue(PicacgBuiltinNetwork.lookup(rules, "picacomic.com.attacker.test").isEmpty())
        assertTrue(PicacgBuiltinNetwork.lookup(rules, "https://picacomic.com").isEmpty())
        assertTrue(PicacgBuiltinNetwork.lookup(rules, "picacomic.com..").isEmpty())
    }

    @Test
    fun `smart candidates augment every fixed root and role alias without replacing baseline`() {
        val rules = PicacgBuiltinNetwork.rules(listOf(" 1.1.1.1 ", "1.1.1.1", "10.0.0.1", "not-an-ip"))
        assertEquals(listOf("104.20.42.9", "172.66.173.71", "1.1.1.1"), rules["picacomic.com"])
        assertEquals(rules["picacomic.com"], rules["storage1.picacomic.com"])
        assertEquals(listOf("104.21.42.75", "172.67.159.59", "1.1.1.1"), rules["go2778.com"])
        assertEquals(rules["go2778.com"], rules["storage-b.go2778.com"])
        assertEquals(listOf("104.21.75.42", "172.67.213.2", "1.1.1.1"), rules["safedataplj.com"])
        assertEquals(rules["safedataplj.com"], rules["img.safedataplj.com"])
    }
}
