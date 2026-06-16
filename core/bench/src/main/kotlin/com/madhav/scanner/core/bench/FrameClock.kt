package com.madhav.scanner.core.bench

/**
 * DESIGN.md §6.2: CameraX with KEEP_ONLY_LATEST silently discards frames, so drops must be
 * inferred from gaps between sensor-domain timestamps rather than observed directly.
 *
 * ```
 * nominalIntervalNs = 1e9 / targetFps
 * gap               = t[i] - t[i-1]
 * dropped[i]        = round(gap / nominalIntervalNs) - 1
 * dropRate          = Σ dropped / (Σ dropped + analyzedCount)
 * ```
 *
 * Two independent clocks feed this: [onFrameDelivered] uses the sensor timestamp
 * (`ImageProxy.imageInfo.timestamp`) for drop/FPS inference, and [onFrameProcessed] uses the
 * wall-clock processing duration to accumulate duty cycle — "how much headroom is left"
 * (DESIGN.md §6.2), independent of whether the sensor is dropping frames.
 */
class FrameClock(private val targetFps: Int) {

    init {
        require(targetFps > 0) { "targetFps must be positive" }
    }

    private val nominalIntervalNs: Long = 1_000_000_000L / targetFps

    private var previousSensorTimestampNs: Long? = null
    private var firstSensorTimestampNs: Long? = null
    private var lastSensorTimestampNs: Long? = null

    private var analyzedCount: Long = 0
    private var totalDropped: Long = 0
    private var totalProcessingNs: Long = 0

    private var firstWallClockNs: Long? = null
    private var lastWallClockNs: Long? = null

    /** Call once per delivered frame with the sensor-domain timestamp. */
    fun onFrameDelivered(sensorTimestampNs: Long, wallClockNs: Long) {
        analyzedCount += 1
        firstSensorTimestampNs = firstSensorTimestampNs ?: sensorTimestampNs
        lastSensorTimestampNs = sensorTimestampNs
        firstWallClockNs = firstWallClockNs ?: wallClockNs
        lastWallClockNs = wallClockNs

        val previous = previousSensorTimestampNs
        if (previous != null) {
            val gap = sensorTimestampNs - previous
            val dropped = (Math.round(gap.toDouble() / nominalIntervalNs) - 1).coerceAtLeast(0)
            totalDropped += dropped
        }
        previousSensorTimestampNs = sensorTimestampNs
    }

    /** Call once per frame with the full analyze() wall-clock duration, for duty cycle. */
    fun onFrameProcessed(processingDurationNs: Long) {
        totalProcessingNs += processingDurationNs
    }

    fun droppedFrameCount(): Long = totalDropped

    fun analyzedFrameCount(): Long = analyzedCount

    /** Fraction of frames the sensor produced that were never analyzed. */
    fun dropRate(): Double {
        val denominator = totalDropped + analyzedCount
        return if (denominator == 0L) 0.0 else totalDropped.toDouble() / denominator
    }

    /** Frames actually analyzed per second, from the sensor timestamp span. */
    fun analysisFps(): Double {
        val first = firstSensorTimestampNs
        val last = lastSensorTimestampNs
        if (first == null || last == null || last == first || analyzedCount < 2) return 0.0
        val spanSeconds = (last - first) / 1_000_000_000.0
        // analyzedCount - 1 intervals span the range between the first and last frame.
        return (analyzedCount - 1) / spanSeconds
    }

    /** Time spent inside analyze() over wall time — headroom indicator (DESIGN.md §6.2). */
    fun dutyCycle(): Double {
        val first = firstWallClockNs
        val last = lastWallClockNs
        if (first == null || last == null || last == first) return 0.0
        val wallSpanNs = (last - first).toDouble()
        return (totalProcessingNs / wallSpanNs).coerceIn(0.0, 1.0)
    }

    fun reset() {
        previousSensorTimestampNs = null
        firstSensorTimestampNs = null
        lastSensorTimestampNs = null
        analyzedCount = 0
        totalDropped = 0
        totalProcessingNs = 0
        firstWallClockNs = null
        lastWallClockNs = null
    }
}
