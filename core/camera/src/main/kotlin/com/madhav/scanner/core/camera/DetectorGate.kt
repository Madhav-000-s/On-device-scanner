package com.madhav.scanner.core.camera

import androidx.camera.core.ImageProxy
import com.madhav.scanner.core.model.DetectionResult

/**
 * The per-frame detector contract (DESIGN.md §4.2). :core:ml provides the real
 * implementation (Phase 8: preprocess -> LiteRT inference -> postprocess). Defining the
 * contract here lets :core:camera's analyzer be built, wired, and compiled well before the
 * model exists — swapping [StubDetectorGate] for the real one touches no camera code.
 */
fun interface DetectorGate {
    fun detect(image: ImageProxy): DetectionResult
}

/** Always reports "nothing detected" — keeps the pipeline running with no model present. */
class StubDetectorGate : DetectorGate {
    override fun detect(image: ImageProxy): DetectionResult = DetectionResult(
        quad = null,
        confidence = 0f,
        coverage = 0f,
        sharpness = 0f,
        frameTimestampNs = image.imageInfo.timestamp,
    )
}
