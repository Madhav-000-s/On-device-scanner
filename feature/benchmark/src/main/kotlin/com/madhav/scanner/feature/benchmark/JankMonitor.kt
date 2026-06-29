package com.madhav.scanner.feature.benchmark

import android.app.Activity
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState

/**
 * DESIGN.md §6.2: "JankStats integration with scan-state annotations, so jank can be
 * attributed to a pipeline stage." Must be constructed after the activity's window/decor
 * view exists (e.g. in onCreate after setContent), which is why this lives in
 * :feature:benchmark rather than :core:bench — it needs a real Window, not just numbers.
 */
class JankMonitor(activity: Activity) {

    private val metricsState = PerformanceMetricsState.getHolderForHierarchy(activity.window.decorView).state

    var jankFrameCount: Long = 0
        private set
    var totalFrameCount: Long = 0
        private set

    private val jankStats: JankStats = JankStats.createAndTrack(activity.window) { frameData ->
        totalFrameCount++
        if (frameData.isJank) jankFrameCount++
    }

    /** Tags subsequent frames with the current [com.madhav.scanner.core.model.ScanState],
     * so a jank spike can be attributed to "ALIGNING" vs "RECOGNIZE" etc.
     */
    fun annotateScanState(scanState: String) {
        metricsState?.putState("scanState", scanState)
    }

    fun jankPercent(): Double = if (totalFrameCount == 0L) 0.0 else jankFrameCount.toDouble() / totalFrameCount * 100.0

    fun stop() {
        jankStats.isTrackingEnabled = false
    }
}
