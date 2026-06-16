package com.madhav.scanner.core.bench

/**
 * DESIGN.md §6.1: fixed-capacity per-stage sample buffers, allocated once. `record()` never
 * allocates — a per-frame allocation on a hot path is exactly the GC-pressure mistake §4.3
 * warns about for the preprocessing buffers, and the same rule applies to the recorder that
 * measures it. Buffers wrap (oldest sample is overwritten) once `capacityPerStage` is
 * exceeded, so a caller that keeps recording past the intended measured-iteration count still
 * gets a bounded, most-recent window rather than unbounded growth.
 *
 * Cold start (DESIGN.md §4.2, Appendix B: "first inference on a fresh Interpreter") is kept
 * completely separate from the stage buffers — it is routinely 10-50x the steady-state
 * figure and must never leak into a percentile calculation.
 */
class LatencyRecorder(private val capacityPerStage: Int = 300) {

    private val buffers: Map<Stage, LongArray> =
        Stage.entries.associateWith { LongArray(capacityPerStage) }
    private val writeIndex: MutableMap<Stage, Int> =
        Stage.entries.associateWith { 0 }.toMutableMap()
    private val filledCount: MutableMap<Stage, Int> =
        Stage.entries.associateWith { 0 }.toMutableMap()

    var coldStartNs: Long? = null
        private set

    fun record(stage: Stage, durationNs: Long) {
        val buffer = buffers.getValue(stage)
        val index = writeIndex.getValue(stage)
        buffer[index] = durationNs
        writeIndex[stage] = (index + 1) % capacityPerStage
        filledCount[stage] = minOf(capacityPerStage, filledCount.getValue(stage) + 1)
    }

    fun recordColdStart(durationNs: Long) {
        coldStartNs = durationNs
    }

    fun sampleCount(stage: Stage): Int = filledCount.getValue(stage)

    /** Null when nothing has been recorded for this stage yet. */
    fun stats(stage: Stage): PercentileStats? {
        val filled = filledCount.getValue(stage)
        if (filled == 0) return null
        val buffer = buffers.getValue(stage)
        val samples = if (filled == capacityPerStage) buffer.copyOf() else buffer.copyOf(filled)
        return PercentileStats.from(samples)
    }

    fun reset() {
        Stage.entries.forEach { stage ->
            writeIndex[stage] = 0
            filledCount[stage] = 0
        }
        coldStartNs = null
    }
}
