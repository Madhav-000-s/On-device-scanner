package com.madhav.scanner.core.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SustainedLoadAnalyzerTest {

    private val windowSizeNs = 1_000_000_000L // 1 second, for readable test math

    @Test
    fun `empty input produces an empty report`() {
        val report = SustainedLoadAnalyzer.analyze(emptyList(), windowSizeNs)
        assertEquals(0, report.windows.size)
        assertNull(report.throttleKneeSeconds)
    }

    @Test
    fun `stable latency across windows never crosses the throttle knee`() {
        val samples = (0 until 10).map { second ->
            TimedSample(elapsedNs = second * windowSizeNs, durationNs = 20_000_000L)
        }
        val report = SustainedLoadAnalyzer.analyze(samples, windowSizeNs)

        assertEquals(10, report.windows.size)
        assertNull(report.throttleKneeSeconds)
    }

    @Test
    fun `detects the throttle knee when p95 crosses 1_5x the first window`() {
        val samples = buildList {
            // Windows 0-2: healthy, p95 = 20ms.
            for (second in 0 until 3) add(TimedSample(second * windowSizeNs, 20_000_000L))
            // Window 3 onward: throttled, p95 = 35ms (1.75x baseline).
            for (second in 3 until 6) add(TimedSample(second * windowSizeNs, 35_000_000L))
        }
        val report = SustainedLoadAnalyzer.analyze(samples, windowSizeNs)

        assertEquals(6, report.windows.size)
        assertEquals(3.0, report.throttleKneeSeconds!!, 0.001)
    }

    @Test
    fun `each window reports its own sample count and percentiles independently`() {
        val samples = listOf(
            TimedSample(0L, 10L),
            TimedSample(1L, 20L),
            TimedSample(windowSizeNs, 999L),
        )
        val report = SustainedLoadAnalyzer.analyze(samples, windowSizeNs)

        assertEquals(2, report.windows.size)
        assertEquals(2, report.windows[0].sampleCount)
        assertEquals(1, report.windows[1].sampleCount)
        assertEquals(999L, report.windows[1].p50)
    }
}
