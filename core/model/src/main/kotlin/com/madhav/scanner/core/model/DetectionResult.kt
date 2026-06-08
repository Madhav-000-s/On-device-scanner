package com.madhav.scanner.core.model

/**
 * Output of the per-frame detector stage (DESIGN.md §4.4). `quad` is null when no
 * document-shaped region clears the area threshold — a real "nothing here" signal,
 * unlike a corner regressor which always emits four corners (DESIGN.md §D2).
 */
data class DetectionResult(
    val quad: Quad?,
    val confidence: Float,
    val coverage: Float,
    val sharpness: Float,
    val frameTimestampNs: Long,
)
