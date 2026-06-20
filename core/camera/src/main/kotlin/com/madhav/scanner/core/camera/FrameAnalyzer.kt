package com.madhav.scanner.core.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.madhav.scanner.core.bench.FrameClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * DESIGN.md §4.2. Runs on the analyzer's single-thread executor (DESIGN.md §4.1 — the LiteRT
 * `Interpreter` [detectorGate] eventually wraps is not thread-safe, so this analyzer is
 * confined to one thread by construction: [com.madhav.scanner.core.camera.CameraBinder]
 * binds it with a single-thread executor, never a pool).
 *
 * The analyzer never touches the UI directly — it only ever writes to [frames]; Compose
 * collects that. `image.close()` runs in `finally` unconditionally: a leaked ImageProxy
 * stalls the whole pipeline within about three frames and looks like a hang (§4.1).
 */
class FrameAnalyzer(
    private val detectorGate: DetectorGate,
    private val frameClock: FrameClock,
) : ImageAnalysis.Analyzer {

    private val _frames = MutableStateFlow<FrameResult?>(null)
    val frames: StateFlow<FrameResult?> = _frames.asStateFlow()

    override fun analyze(image: ImageProxy) {
        val tCallback = System.nanoTime()
        try {
            frameClock.onFrameDelivered(sensorTimestampNs = image.imageInfo.timestamp, wallClockNs = tCallback)

            val detection = detectorGate.detect(image)
            val tEnd = System.nanoTime()

            frameClock.onFrameProcessed(tEnd - tCallback)
            _frames.value = FrameResult(detection, tEnd)
        } finally {
            image.close()
        }
    }
}
