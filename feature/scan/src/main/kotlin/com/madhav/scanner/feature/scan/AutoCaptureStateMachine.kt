package com.madhav.scanner.feature.scan

import com.madhav.scanner.core.model.DetectionResult
import com.madhav.scanner.core.model.Quad
import com.madhav.scanner.core.model.ScanState
import kotlin.math.hypot

/**
 * DESIGN.md §4.5's auto-capture state machine. Hysteresis on the SEARCHING<->ALIGNING edge
 * (enter at 0.4, exit at 0.3) prevents overlay flicker at the threshold. Eight consecutive
 * frames satisfying the STABLE criteria (~270ms at 30 FPS) is long enough to reject a hand
 * still settling, short enough not to feel sluggish.
 *
 * This only ever produces SEARCHING / ALIGNING / STABLE / CAPTURING — the states after
 * CAPTURING (RECOGNIZE/PARSING/RESULT) are driven by the capture pipeline itself, not by
 * per-frame detections, so [onDetection] is never called while in those states.
 */
class AutoCaptureStateMachine {

    private var state = ScanState.SEARCHING
    private var stableFrameCount = 0
    private var previousQuad: Quad? = null

    fun onDetection(result: DetectionResult): ScanState {
        when (state) {
            ScanState.SEARCHING -> {
                if (result.confidence >= ENTER_CONFIDENCE) {
                    state = ScanState.ALIGNING
                    stableFrameCount = 0
                }
            }

            ScanState.ALIGNING, ScanState.STABLE -> {
                if (result.confidence < EXIT_CONFIDENCE) {
                    reset()
                    return state
                }

                val quad = result.quad
                val drift = if (quad != null && previousQuad != null) quadDrift(quad, previousQuad!!) else Float.MAX_VALUE
                val meetsStableCriteria = quad != null &&
                    result.coverage >= MIN_COVERAGE &&
                    result.sharpness >= MIN_SHARPNESS &&
                    drift < MAX_DRIFT_PX

                if (meetsStableCriteria) {
                    stableFrameCount++
                    state = if (stableFrameCount >= REQUIRED_STABLE_FRAMES) ScanState.CAPTURING else ScanState.STABLE
                } else {
                    stableFrameCount = 0
                    state = ScanState.ALIGNING
                }
                if (quad != null) previousQuad = quad
            }

            else -> Unit
        }
        return state
    }

    fun reset() {
        state = ScanState.SEARCHING
        stableFrameCount = 0
        previousQuad = null
    }

    private fun quadDrift(a: Quad, b: Quad): Float {
        val pairs = a.points().zip(b.points())
        return pairs.map { (p1, p2) -> hypot((p1.x - p2.x).toDouble(), (p1.y - p2.y).toDouble()).toFloat() }.average().toFloat()
    }

    companion object {
        const val ENTER_CONFIDENCE = 0.4f
        const val EXIT_CONFIDENCE = 0.3f
        const val MIN_COVERAGE = 0.35f

        // DESIGN.md §4.5 names this "a tuned hyperparameter" but gives no number (unlike
        // threshold=0.5, which it does specify) — it needs on-device tuning against real
        // Laplacian-variance readings, which this environment has no device to produce.
        // 50f is a conventional starting point for 8-bit variance-of-Laplacian sharpness
        // gates in the literature, not a value measured here.
        const val MIN_SHARPNESS = 50f

        const val MAX_DRIFT_PX = 8f
        const val REQUIRED_STABLE_FRAMES = 8
    }
}
