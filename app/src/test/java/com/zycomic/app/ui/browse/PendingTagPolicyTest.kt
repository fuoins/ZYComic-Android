package com.zycomic.app.ui.browse

import com.zycomic.app.platform.ContentPlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PendingTagPolicyTest {

    @Test
    fun `reuses first Manwa filter regardless of its name in a mixed platform tab list`() {
        val tabs = listOf(
            tab("pic-filter", "mw", platform = ContentPlatform.PICACG),
            tab("manwa-special", "Top picks", kind = TabKind.SPECIAL),
            tab("manwa-filter-first", "Favorites", filter = FilterSnapshot(tags = listOf("old"))),
            tab("manwa-filter-later", "mw (2)", filter = FilterSnapshot(gender = 4)),
        )

        val target = PendingTagPolicy.resolve(tabs, manwaEnabled = true)

        assertSame(tabs, target?.tabs)
        assertEquals(2, target?.index)
        assertEquals(2, target?.tabs?.count { it.platformId == ContentPlatform.MANWA && it.kind == TabKind.FILTER })
    }

    @Test
    fun `creates default empty tag filter when the tab list is empty`() {
        val target = PendingTagPolicy.resolve(emptyList(), manwaEnabled = true)!!
        val created = target.tabs[target.index]

        assertEquals(0, target.index)
        assertEquals(1, target.tabs.size)
        assertEquals("mw", created.name)
        assertEquals(TabKind.FILTER, created.kind)
        assertEquals(ContentPlatform.MANWA, created.platformId)
        assertEquals(FilterSnapshot(), created.filter)
        assertTrue(created.id.startsWith("custom_"))
    }

    @Test
    fun `creates Manwa filter at the end when there is no Manwa tab`() {
        val otherPlatformTabs = listOf(
            tab("pic-filter", "Pic categories", platform = ContentPlatform.PICACG),
            tab("jm-filter", "JM categories", platform = ContentPlatform.JM),
            tab("pic-special", "Special", platform = ContentPlatform.PICACG, kind = TabKind.SPECIAL),
        )

        val target = PendingTagPolicy.resolve(otherPlatformTabs, manwaEnabled = true)!!

        assertEquals(otherPlatformTabs.size, target.index)
        assertEquals(otherPlatformTabs, target.tabs.take(otherPlatformTabs.size))
        assertEquals(ContentPlatform.MANWA, target.tabs.last().platformId)
        assertEquals(FilterSnapshot(), target.tabs.last().filter)
    }

    @Test
    fun `avoids names already used on any platform`() {
        val tabs = listOf(
            tab("one", "mw", platform = ContentPlatform.PICACG),
            tab("two", "mw (2)", platform = ContentPlatform.JM),
        )

        val created = PendingTagPolicy.resolve(tabs, manwaEnabled = true)!!.tabs.last()

        assertEquals("mw (3)", created.name)
    }

    @Test
    fun `repeated taps reuse the single filter created by the first tap`() {
        val original = listOf(tab("pic", "Browse", platform = ContentPlatform.PICACG))
        val first = PendingTagPolicy.resolve(original, manwaEnabled = true)!!
        val second = PendingTagPolicy.resolve(first.tabs, manwaEnabled = true)!!
        val third = PendingTagPolicy.resolve(second.tabs, manwaEnabled = true)!!

        assertEquals(original.size + 1, third.tabs.size)
        assertEquals(first.index, second.index)
        assertEquals(first.index, third.index)
        assertEquals(first.tabs[first.index].id, third.tabs[third.index].id)
    }

    @Test
    fun `disabled Manwa refuses creation without mutating the supplied list`() {
        val tabs = listOf(tab("pic", "Keep me", platform = ContentPlatform.PICACG))

        val target = PendingTagPolicy.resolve(tabs, manwaEnabled = false)

        assertNull(target)
        assertEquals(listOf("Keep me"), tabs.map { it.name })
    }

    @Test
    fun `disabled Manwa refuses even when a matching filter already exists`() {
        val tabs = listOf(tab("existing", "Existing filter", filter = FilterSnapshot(tags = listOf("saved"))))
        val before = tabs.toList()

        val target = PendingTagPolicy.resolve(tabs, manwaEnabled = false)

        assertNull(target)
        assertEquals(before, tabs)
        assertEquals(listOf("saved"), tabs.single().filter?.tags)
    }

    @Test
    fun `successful apply consumes only after apply and in order`() {
        val events = mutableListOf<String>()

        PendingTagPolicy.applyAndConsume(
            tag = "romance",
            apply = { value ->
                events += "apply:$value"
                true
            },
            onConsumed = { events += "consume" },
        )

        assertEquals(listOf("apply:romance", "consume"), events)
    }

    @Test
    fun `unsuccessful apply does not consume`() {
        var consumed = false

        PendingTagPolicy.applyAndConsume("action", { false }) { consumed = true }

        assertFalse(consumed)
    }

    @Test
    fun `throwing apply propagates failure without consuming`() {
        var consumed = false
        val failure = IllegalStateException("apply failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            PendingTagPolicy.applyAndConsume("action", { throw failure }) { consumed = true }
        }

        assertSame(failure, thrown)
        assertFalse(consumed)
    }

    @Test
    fun `reusing a filter preserves names order built in metadata and filter identities`() {
        val filterSnapshot = FilterSnapshot(gender = 1, tags = listOf("saved"), area = 3, end = 1, st = 0)
        val tabs = listOf(
            tab("pic", "跨平台", platform = ContentPlatform.PICACG, builtin = true),
            BrowseTabItem(
                id = "latest",
                name = "Latest page",
                kind = TabKind.SPECIAL,
                builtin = true,
                pageType = BrowsePageType.LATEST,
                platformId = ContentPlatform.MANWA,
            ),
            tab("custom", "My filters", filter = filterSnapshot, builtin = true),
        )
        val namesBefore = tabs.map { it.name }

        val target = PendingTagPolicy.resolve(tabs, manwaEnabled = true)!!

        assertSame(tabs, target.tabs)
        assertEquals(namesBefore, target.tabs.map { it.name })
        assertEquals(listOf("pic", "latest", "custom"), target.tabs.map { it.id })
        assertTrue(target.tabs.all { it.builtin })
        assertEquals(BrowsePageType.LATEST, target.tabs[1].pageType)
        assertSame(filterSnapshot, target.tabs[2].filter)
    }

    @Test
    fun `temporary filter replaces only tags and never mutates the preset`() {
        val preset = FilterSnapshot(gender = 1, tags = listOf("preset-a", "preset-b"), area = 4, end = 1, st = 0)
        val item = tab("preset", "Preset", filter = preset)

        val temporary = PendingTagPolicy.temporaryFilter(item, "clicked-tag")

        assertEquals(FilterSnapshot(gender = 1, tags = listOf("clicked-tag"), area = 4, end = 1, st = 0), temporary)
        assertSame(preset, item.filter)
        assertEquals(listOf("preset-a", "preset-b"), item.filter?.tags)
    }

    @Test
    fun `temporary tag filter leaves persisted preset and unrelated session overrides isolated`() {
        val preset = FilterSnapshot(gender = 3, tags = listOf("saved"), area = 2, end = 0, st = 1)
        val item = tab("target", "Target", filter = preset)
        val sessionFilters = SessionTabFilters<FilterSnapshot>()
        val otherSessionValue = FilterSnapshot(gender = 0, tags = listOf("other-session"))
        sessionFilters.set("jm:${item.id}", otherSessionValue)
        sessionFilters.set("manwa:${item.id}", FilterSnapshot(gender = 2, tags = listOf("target-session")))

        val temporary = PendingTagPolicy.temporaryFilter(item, "temporary")
        sessionFilters.set("manwa:${item.id}", temporary)

        assertEquals(listOf("temporary"), temporary.tags)
        assertEquals(preset, item.filter)
        assertSame(otherSessionValue, sessionFilters.get("jm:${item.id}") { error("must retain existing override") })
        assertSame(temporary, sessionFilters.get("manwa:${item.id}") { error("must retain temporary filter") })
        assertEquals(preset, SessionTabFilters<FilterSnapshot>().get("manwa:${item.id}") { item.filter!! })
    }

    private fun tab(
        id: String,
        name: String,
        platform: ContentPlatform = ContentPlatform.MANWA,
        kind: TabKind = TabKind.FILTER,
        filter: FilterSnapshot? = if (platform == ContentPlatform.MANWA && kind == TabKind.FILTER) FilterSnapshot() else null,
        builtin: Boolean = false,
    ) = BrowseTabItem(
        id = id,
        name = name,
        kind = kind,
        builtin = builtin,
        filter = filter,
        platformId = platform,
    )
}
