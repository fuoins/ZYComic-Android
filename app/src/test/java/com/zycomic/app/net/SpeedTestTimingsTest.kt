package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedTestTimingsTest {
    private class Fixture(startNanos: Long = 0L) {
        var nowNanos = startNanos
        val published = mutableListOf<Map<SpeedTestTimings.Phase, Long>>()
        val timings = SpeedTestTimings(
            nanoTime = { nowNanos },
            publish = { published += it },
        )
    }

    @Test
    fun `total measures wall time rather than adding overlapping spans`() {
        val fixture = Fixture()
        fixture.nowNanos = 10_000_000L
        val lines = fixture.timings.start(SpeedTestTimings.Phase.LINES)
        fixture.nowNanos = 20_000_000L
        val images = fixture.timings.start(SpeedTestTimings.Phase.IMAGES_INCLUDING_SAMPLE)
        fixture.nowNanos = 100_000_000L

        lines.close()
        images.close()
        fixture.timings.finish()

        val result = fixture.published.single()
        assertEquals(100L, result.getValue(SpeedTestTimings.Phase.TOTAL))
        assertEquals(90L, result.getValue(SpeedTestTimings.Phase.LINES))
        assertEquals(80L, result.getValue(SpeedTestTimings.Phase.IMAGES_INCLUDING_SAMPLE))
        assertTrue(
            result.getValue(SpeedTestTimings.Phase.TOTAL) <
                result.getValue(SpeedTestTimings.Phase.LINES) + result.getValue(SpeedTestTimings.Phase.IMAGES_INCLUDING_SAMPLE),
        )
    }

    @Test
    fun `repeated phase spans accumulate nanoseconds before conversion to milliseconds`() {
        val fixture = Fixture()
        val first = fixture.timings.start(SpeedTestTimings.Phase.CONFIG_FETCH)
        fixture.nowNanos = 600_000L
        first.close()

        val second = fixture.timings.start(SpeedTestTimings.Phase.CONFIG_FETCH)
        fixture.nowNanos = 1_200_000L
        second.close()
        fixture.timings.finish()

        assertEquals(1L, fixture.published.single().getValue(SpeedTestTimings.Phase.CONFIG_FETCH))
    }

    @Test
    fun `span close and finish are idempotent`() {
        val fixture = Fixture()
        val span = fixture.timings.start(SpeedTestTimings.Phase.CONFIG_CHECK)
        fixture.nowNanos = 5_000_000L
        span.close()
        fixture.nowNanos = 10_000_000L
        span.close()
        fixture.nowNanos = 20_000_000L
        fixture.timings.finish()
        fixture.nowNanos = 30_000_000L
        fixture.timings.close()
        fixture.timings.finish()

        assertEquals(1, fixture.published.size)
        assertEquals(5L, fixture.published.single().getValue(SpeedTestTimings.Phase.CONFIG_CHECK))
        assertEquals(20L, fixture.published.single().getValue(SpeedTestTimings.Phase.TOTAL))
    }

    @Test
    fun `finish records an active span when work is interrupted`() {
        val fixture = Fixture(10_000_000L)
        fixture.timings.start(SpeedTestTimings.Phase.LINE_PREPARATION)
        fixture.nowNanos = 25_000_000L

        fixture.timings.finish()

        assertEquals(15L, fixture.published.single().getValue(SpeedTestTimings.Phase.LINE_PREPARATION))
    }

    @Test
    fun `phases that never occurred are omitted`() {
        val fixture = Fixture()
        fixture.nowNanos = 1_000_000L

        fixture.timings.finish()

        assertEquals(setOf(SpeedTestTimings.Phase.TOTAL), fixture.published.single().keys)
        assertFalse(fixture.published.single().containsKey(SpeedTestTimings.Phase.CONFIG_FETCH))
    }

    @Test
    fun `publishing failure does not escape finish`() {
        val timings = SpeedTestTimings(
            nanoTime = { 0L },
            publish = { throw IllegalStateException("publish failure") },
        )

        assertDoesNotThrow { timings.finish() }
    }

    @Test
    fun `use closes timings on failure and propagates the original exception`() {
        val fixture = Fixture()
        val failure = IllegalArgumentException("probe failed")

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            fixture.timings.use {
                fixture.nowNanos = 10_000_000L
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(10L, fixture.published.single().getValue(SpeedTestTimings.Phase.TOTAL))
    }

    @Test
    fun `published output contains only phase keys and long durations`() {
        var result: Map<*, *>? = null
        val timings = SpeedTestTimings(
            nanoTime = { 2_000_000L },
            publish = { result = it },
        )
        timings.start(SpeedTestTimings.Phase.CONFIG_VALIDATION).close()
        timings.start(SpeedTestTimings.Phase.IMAGES).close()

        timings.finish()

        val published = requireNotNull(result)
        assertEquals(
            setOf(SpeedTestTimings.Phase.TOTAL, SpeedTestTimings.Phase.CONFIG_VALIDATION, SpeedTestTimings.Phase.IMAGES),
            published.keys,
        )
        assertTrue(published.keys.all { it is SpeedTestTimings.Phase })
        assertTrue(published.values.all { it is Long })
    }

    @Test
    fun `clock moving backwards never produces negative durations`() {
        val fixture = Fixture(100L)
        val span = fixture.timings.start(SpeedTestTimings.Phase.CONFIG_APPLY)
        fixture.nowNanos = 90L
        span.close()
        fixture.nowNanos = 80L

        fixture.timings.finish()

        val result = fixture.published.single()
        assertEquals(0L, result.getValue(SpeedTestTimings.Phase.CONFIG_APPLY))
        assertEquals(0L, result.getValue(SpeedTestTimings.Phase.TOTAL))
        assertTrue(result.values.all { it >= 0L })
    }
}
