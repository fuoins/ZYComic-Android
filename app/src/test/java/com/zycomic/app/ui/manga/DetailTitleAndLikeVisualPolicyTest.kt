package com.zycomic.app.ui.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DetailTitleAndLikeVisualPolicyTest {
    @Test
    fun `title expansion is independent and changes only its own line limit`() {
        assertEquals(3, detailTitleMaxLines(expanded = false))
        assertEquals(Int.MAX_VALUE, detailTitleMaxLines(expanded = true))
    }

    @Test
    fun `JM like affordance distinguishes liked and unliked in enabled and disabled states`() {
        val states = listOf(
            jmLikeVisualState(liked = true, enabled = true),
            jmLikeVisualState(liked = false, enabled = true),
            jmLikeVisualState(liked = true, enabled = false),
            jmLikeVisualState(liked = false, enabled = false),
        )

        assertEquals(4, states.toSet().size)
        assertEquals(
            setOf(
                JmLikeVisualState(liked = true, enabled = true),
                JmLikeVisualState(liked = false, enabled = true),
                JmLikeVisualState(liked = true, enabled = false),
                JmLikeVisualState(liked = false, enabled = false),
            ),
            states.toSet(),
        )
    }
}
