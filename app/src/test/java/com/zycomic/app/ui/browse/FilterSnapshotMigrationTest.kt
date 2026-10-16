package com.zycomic.app.ui.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class FilterSnapshotMigrationTest {

    @Test
    fun `legacy area and end values migrate without changing tab order or other filters`() {
        val areaMappings = listOf(0 to 0, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 1)
        val endMappings = listOf(0 to 0, 1 to 2, 2 to 1)
        val tabs = areaMappings.flatMapIndexed { index, (oldArea, _) ->
            endMappings.mapIndexed { endIndex, (oldEnd, _) ->
                BrowseTabItem(
                    id = "custom-$index-$endIndex",
                    name = "自定义 $index/$endIndex",
                    kind = TabKind.FILTER,
                    builtin = false,
                    filter = FilterSnapshot(
                        gender = 3,
                        tags = listOf("热血", "冒险"),
                        area = oldArea,
                        end = oldEnd,
                        st = 1,
                    ),
                )
            }
        }
        val special = BrowseTabItem(
            id = "special",
            name = "最近更新",
            kind = TabKind.SPECIAL,
            pageType = BrowsePageType.LATEST,
        )

        val migrated = BrowseTabStore.migrateLegacyFilterValues(tabs + special, seedVersion = 4)

        assertEquals(tabs.map { it.id } + special.id, migrated.map { it.id })
        assertEquals(tabs.map { it.name } + special.name, migrated.map { it.name })
        assertSame(special, migrated.last())
        migrated.dropLast(1).forEachIndexed { index, tab ->
            val original = tabs[index]
            val filter = requireNotNull(tab.filter)
            assertEquals(
                original.filter?.copy(
                    area = areaMappings[index / endMappings.size].second,
                    end = endMappings[index % endMappings.size].second,
                ),
                filter,
            )
            assertEquals(original.filter?.gender, filter.gender)
            assertEquals(original.filter?.tags, filter.tags)
            assertEquals(original.filter?.st, filter.st)
        }
    }

    @Test
    fun `seed version five prevents remapping on subsequent loads`() {
        val tabs = listOf(
            BrowseTabItem(
                id = "custom",
                name = "自定义",
                kind = TabKind.FILTER,
                filter = FilterSnapshot(area = 2, end = 1),
            ),
        )

        assertSame(tabs, BrowseTabStore.migrateLegacyFilterValues(tabs, seedVersion = 5))
    }
}
