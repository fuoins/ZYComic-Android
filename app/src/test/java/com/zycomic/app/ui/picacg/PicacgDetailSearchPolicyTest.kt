package com.zycomic.app.ui.picacg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PicacgDetailSearchPolicyTest {
    @Test
    fun `title author and tag routes use Picacg provider and preserve their query`() {
        val values = listOf(
            PicacgDetailSearchPolicy.Source.TITLE to " title ",
            PicacgDetailSearchPolicy.Source.AUTHOR to " author ",
            PicacgDetailSearchPolicy.Source.TAG to " tag ",
        )

        assertEquals("picacg", PicacgDetailSearchPolicy.SEARCH_PROVIDER)
        assertEquals(listOf("title", "author", "tag"), values.map { (source, value) -> PicacgDetailSearchPolicy.query(source, value) })
    }

    @Test
    fun `blank search targets do not open search`() {
        PicacgDetailSearchPolicy.Source.entries.forEach { source ->
            assertNull(PicacgDetailSearchPolicy.query(source, "  "))
        }
    }
}
