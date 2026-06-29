package com.madhav.scanner.core.bench

/** One sample from a long-running inference loop: when it happened and how long it took. */
data class TimedSample(val elapsedNs: Long, val durationNs: Long)

data class WindowStats(
    val windowStartNs: Long,
    val p50: Long,
    val p95: Long,
    val sampleCount: Int,
)

data class SustainedLoadReport(
    val windows: List<WindowStats>,
    /** Elapsed seconds at which p95 first crosses 1.5x the first window's p95, or null if
     * it never does across the run (DESIGN.md §6.3). */
    val throttleKneeSeconds: Double?,
)

/**
 * DESIGN.md §6.3: "A 30-second benchmark on a cold phone is close to a lie. Budget SoCs
 * throttle hard." Buckets samples into fixed windows and reports p50/p95 per window, plus
 * the throttle knee -- the point past which the device is visibly no longer running at its
 * cold-start speed. "The sustained-load curve is the most informative single chart this
 * project will produce."
 */
object SustainedLoadAnalyzer {

    private const val THROTTLE_KNEE_FACTOR = 1.5

    fun analyze(samples: List<TimedSample>, windowSizeNs: Long): SustainedLoadReport {
        if (samples.isEmpty()) return SustainedLoadReport(emptyList(), null)

        val sorted = samples.sortedBy { it.elapsedNs }
        val windows = sorted.groupBy { it.elapsedNs / windowSizeNs }
            .toSortedMap()
            .map { (windowIndex, windowSamples) ->
                val durations = windowSamples.map { it.durationNs }.toLongArray()
                val stats = PercentileStats.from(durations)
                WindowStats(
                    windowStartNs = windowIndex * windowSizeNs,
                    p50 = stats.p50,
                    p95 = stats.p95,
                    sampleCount = stats.count,
                )
            }

        val baselineP95 = windows.first().p95
        val kneeWindow = windows.firstOrNull { it.p95 >= baselineP95 * THROTTLE_KNEE_FACTOR }
        val throttleKneeSeconds = kneeWindow?.let { it.windowStartNs / 1_000_000_000.0 }

        return SustainedLoadReport(windows, throttleKneeSeconds)
    }
}
