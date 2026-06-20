package com.madhav.scanner.core.camera

import com.madhav.scanner.core.model.DetectionResult

data class FrameResult(
    val detection: DetectionResult,
    val processedAtNs: Long,
)
