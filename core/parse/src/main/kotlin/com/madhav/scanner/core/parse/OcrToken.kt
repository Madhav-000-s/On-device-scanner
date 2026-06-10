package com.madhav.scanner.core.parse

/** Axis-aligned box in the rectified capture's pixel space. */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** Vertical overlap fraction relative to the shorter of the two boxes' heights. */
    fun verticalOverlapFraction(other: BoundingBox): Float {
        val overlapTop = maxOf(top, other.top)
        val overlapBottom = minOf(bottom, other.bottom)
        val overlap = (overlapBottom - overlapTop).coerceAtLeast(0f)
        val shorter = minOf(height, other.height)
        return if (shorter <= 0f) 0f else overlap / shorter
    }
}

/**
 * One recognized word-like unit, equivalent to an ML Kit `Text.Element` (DESIGN.md §7.2:
 * "blocks -> lines -> elements, each with a bounding box and confidence"). This module is
 * pure Kotlin/JVM (DESIGN.md §3), so it depends on this plain type rather than the Android
 * ML Kit SDK types directly — the :core:ocr module maps ML Kit's real output onto this shape.
 */
data class OcrToken(
    val text: String,
    val box: BoundingBox,
    val confidence: Float,
)
