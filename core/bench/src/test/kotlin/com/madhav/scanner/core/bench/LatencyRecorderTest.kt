package com.madhav.scanner.core.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyRecorderTest {

    @Test
    fun `unrecorded stage returns null stats, not a fabricated zero`() {
        val recorder = LatencyRecorder(capacityPerStage = 10)
        assertNull(recorder.stats(Stage.PREPROCESS))
        assertEquals(0, recorder.sampleCount(Stage.PREPROCESS))
    }

    @Test
    fun `records below capacity report exactly what was recorded`() {
        val recorder = LatencyRecorder(capacityPerStage = 10)
        listOf(1L, 2L, 3L).forEach { recorder.record(Stage.INFERENCE_WALL, it) }

        assertEquals(3, recorder.sampleCount(Stage.INFERENCE_WALL))
        assertEquals(2L, recorder.stats(Stage.INFERENCE_WALL)!!.p50)
    }

    @Test
    fun `ring buffer wraps and keeps only the most recent capacity samples`() {
        val recorder = LatencyRecorder(capacityPerStage = 5)
        // Record 1..8 into a 5-slot buffer: only 4,5,6,7,8 should remain.
        (1L..8L).forEach { recorder.record(Stage.POSTPROCESS, it) }

        assertEquals(5, recorder.sampleCount(Stage.POSTPROCESS))
        val stats = recorder.stats(Stage.POSTPROCESS)!!
        assertEquals(4L, stats.min)
        assertEquals(8L, stats.max)
    }

    @Test
    fun `stages are recorded independently of each other`() {
        val recorder = LatencyRecorder(capacityPerStage = 10)
        recorder.record(Stage.PREPROCESS, 5L)
        recorder.record(Stage.INFERENCE_NATIVE, 500L)

        assertEquals(5L, recorder.stats(Stage.PREPROCESS)!!.p50)
        assertEquals(500L, recorder.stats(Stage.INFERENCE_NATIVE)!!.p50)
        assertNull(recorder.stats(Stage.POSTPROCESS))
    }

    @Test
    fun `cold start is tracked separately and never enters stage percentiles`() {
        val recorder = LatencyRecorder(capacityPerStage = 10)
        recorder.recordColdStart(50_000_000L)
        recorder.record(Stage.INFERENCE_WALL, 5_000_000L)

        assertEquals(50_000_000L, recorder.coldStartNs)
        assertEquals(1, recorder.sampleCount(Stage.INFERENCE_WALL))
        assertEquals(5_000_000L, recorder.stats(Stage.INFERENCE_WALL)!!.p50)
    }

    @Test
    fun `reset clears samples and cold start`() {
        val recorder = LatencyRecorder(capacityPerStage = 10)
        recorder.record(Stage.PREPROCESS, 1L)
        recorder.recordColdStart(1L)

        recorder.reset()

        assertNull(recorder.stats(Stage.PREPROCESS))
        assertEquals(null, recorder.coldStartNs)
    }
}
