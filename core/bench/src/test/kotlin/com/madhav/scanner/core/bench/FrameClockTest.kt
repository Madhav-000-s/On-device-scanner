package com.madhav.scanner.core.bench

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameClockTest {

    // targetFps = 10 -> nominalIntervalNs = 100_000_000 exactly, so gaps that are an exact
    // multiple of it produce exact (not floating-rounded) expected drop counts.
    private val nominalIntervalNs = 100_000_000L

    @Test
    fun `a single frame has no drops and reports zero fps (no span yet)`() {
        val clock = FrameClock(targetFps = 10)
        clock.onFrameDelivered(sensorTimestampNs = 0L, wallClockNs = 0L)

        assertEquals(0L, clock.droppedFrameCount())
        assertEquals(0.0, clock.dropRate(), 0.0001)
        assertEquals(0.0, clock.analysisFps(), 0.0001)
    }

    @Test
    fun `back-to-back frames at exactly the nominal interval drop nothing`() {
        val clock = FrameClock(targetFps = 10)
        repeat(5) { i -> clock.onFrameDelivered(i * nominalIntervalNs, i * nominalIntervalNs) }

        assertEquals(0L, clock.droppedFrameCount())
        assertEquals(0.0, clock.dropRate(), 0.0001)
        assertEquals(10.0, clock.analysisFps(), 0.01)
    }

    @Test
    fun `a gap of exactly k times nominal drops k minus one frames`() {
        val clock = FrameClock(targetFps = 10)
        clock.onFrameDelivered(0L, 0L)
        // Exactly 3x the nominal interval -> 2 frames inferred dropped.
        clock.onFrameDelivered(3 * nominalIntervalNs, 3 * nominalIntervalNs)

        assertEquals(2L, clock.droppedFrameCount())
        // dropRate = dropped / (dropped + analyzed) = 2 / (2 + 2)
        assertEquals(0.5, clock.dropRate(), 0.0001)
    }

    @Test
    fun `drop rate accumulates across multiple gaps`() {
        val clock = FrameClock(targetFps = 10)
        clock.onFrameDelivered(0L, 0L)
        clock.onFrameDelivered(nominalIntervalNs, nominalIntervalNs) // gap = 1x nominal, no drop
        clock.onFrameDelivered(3 * nominalIntervalNs, 3 * nominalIntervalNs) // gap = 2x nominal, +1 dropped

        assertEquals(1L, clock.droppedFrameCount())
        assertEquals(3L, clock.analyzedFrameCount())
        // 1 / (1 + 3)
        assertEquals(0.25, clock.dropRate(), 0.0001)
    }

    @Test
    fun `duty cycle is processing time over wall-clock span`() {
        val clock = FrameClock(targetFps = 10)
        clock.onFrameDelivered(0L, wallClockNs = 0L)
        clock.onFrameProcessed(processingDurationNs = 20_000_000L) // 20ms of work

        clock.onFrameDelivered(nominalIntervalNs, wallClockNs = nominalIntervalNs) // 100ms later
        clock.onFrameProcessed(processingDurationNs = 20_000_000L)

        // 40ms of processing over a 100ms wall span = 0.4 duty cycle.
        assertEquals(0.4, clock.dutyCycle(), 0.0001)
    }

    @Test
    fun `reset clears all accumulated state`() {
        val clock = FrameClock(targetFps = 10)
        clock.onFrameDelivered(0L, 0L)
        clock.onFrameDelivered(3 * nominalIntervalNs, 3 * nominalIntervalNs)
        clock.onFrameProcessed(10L)

        clock.reset()

        assertEquals(0L, clock.droppedFrameCount())
        assertEquals(0L, clock.analyzedFrameCount())
        assertEquals(0.0, clock.dutyCycle(), 0.0001)
    }
}
