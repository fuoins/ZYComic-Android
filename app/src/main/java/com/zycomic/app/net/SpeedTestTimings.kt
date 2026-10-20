package com.zycomic.app.net

/** Wall-clock spans may overlap. Never add phase/platform durations to infer total time. */
internal class SpeedTestTimings(
    private val nanoTime: () -> Long = System::nanoTime,
    private val publish: (Map<Phase, Long>) -> Unit,
) : AutoCloseable {
    enum class Phase {
        TOTAL,
        CONFIG_CHECK,
        CONFIG_FETCH,
        CONFIG_VALIDATION,
        CONFIG_APPLY,
        PICACG_LINE_PREPARATION,
        PICACG_LINES,
        PICACG_IMAGE_PREPARATION,
        PICACG_IMAGES,
        LINE_PREPARATION,
        LINES,
        CONFIG_WAIT,
        IMAGE_PREPARATION,
        IMAGE_SAMPLE_FETCH_FALLBACK,
        IMAGES,
        IMAGES_INCLUDING_SAMPLE,
        PROBE_QUEUE_WAIT,
    }

    private val started = nanoTime()
    private val elapsedNanos = linkedMapOf<Phase, Long>()
    private val active = mutableSetOf<Span>()
    private var finished = false

    inner class Span internal constructor(private val phase: Phase, private val start: Long) : AutoCloseable {
        override fun close() = synchronized(this@SpeedTestTimings) {
            if (active.remove(this)) {
                elapsedNanos[phase] = (elapsedNanos[phase] ?: 0L) + (nanoTime() - start).coerceAtLeast(0L)
            }
        }
    }

    @Synchronized
    fun start(phase: Phase): Span = Span(phase, nanoTime()).also {
        check(!finished)
        active.add(it)
    }

    /** Adds accumulated scheduler wait separately; it is never included in endpoint probe latency. */
    @Synchronized
    fun record(phase: Phase, elapsedMs: Long) {
        if (finished || elapsedMs <= 0L) return
        elapsedNanos[phase] = (elapsedNanos[phase] ?: 0L) + elapsedMs * 1_000_000L
    }

    override fun close() = finish()

    /** Finally-safe, idempotent; interrupted spans represent time actually spent, not success. */
    @Synchronized
    fun finish() {
        if (finished) return
        active.toList().forEach { it.close() }
        elapsedNanos[Phase.TOTAL] = (nanoTime() - started).coerceAtLeast(0L)
        finished = true
        // Diagnostics must never change the caller's result or cancellation behavior.
        runCatching { publish(elapsedNanos.mapValues { it.value / 1_000_000L }) }
    }
}
