package com.madhav.scanner.core.parse

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class DeskewResult(
    val tokens: List<OcrToken>,
    val angleRadians: Float,
)

/**
 * DESIGN.md §7.3 step 1: estimate the residual text-baseline angle and rotate boxes.
 * "Residual" because the perspective warp at capture (§7.1) already removes gross
 * skew — this corrects what's left (a few degrees from an imperfect quad or a receipt
 * that wasn't perfectly flat).
 *
 * The angle is estimated from pairs of tokens that are likely on the same visual baseline:
 * far apart horizontally, close together vertically. The median slope across those pairs is
 * robust to the outlier pairs that straddle two different rows.
 */
object Deskew {

    private const val MIN_HORIZONTAL_SEPARATION_FACTOR = 1.5f
    private const val MAX_SLOPE_MAGNITUDE = 0.3f // ~17 degrees; beyond this it's not "residual"

    fun estimateSkewAngleRadians(tokens: List<OcrToken>): Float {
        if (tokens.size < 2) return 0f

        val averageWidth = tokens.map { it.box.width }.average().toFloat()
        val minSeparation = averageWidth * MIN_HORIZONTAL_SEPARATION_FACTOR

        val slopes = mutableListOf<Float>()
        for (i in tokens.indices) {
            for (j in i + 1 until tokens.size) {
                val a = tokens[i].box
                val b = tokens[j].box
                val dx = b.centerX - a.centerX
                if (abs(dx) < minSeparation) continue
                val dy = b.centerY - a.centerY
                val slope = dy / dx
                if (abs(slope) <= MAX_SLOPE_MAGNITUDE) slopes += slope
            }
        }
        if (slopes.isEmpty()) return 0f

        val sorted = slopes.sorted()
        val median = sorted[sorted.size / 2]
        return atan2(median.toDouble(), 1.0).toFloat()
    }

    fun deskew(tokens: List<OcrToken>): DeskewResult {
        if (tokens.isEmpty()) return DeskewResult(tokens, 0f)

        val angle = estimateSkewAngleRadians(tokens)
        if (angle == 0f) return DeskewResult(tokens, 0f)

        val pivotX = tokens.map { it.box.centerX }.average().toFloat()
        val pivotY = tokens.map { it.box.centerY }.average().toFloat()
        val cosA = cos(-angle.toDouble()).toFloat()
        val sinA = sin(-angle.toDouble()).toFloat()

        val rotated = tokens.map { token ->
            val box = token.box
            val dx = box.centerX - pivotX
            val dy = box.centerY - pivotY
            val newCenterX = pivotX + dx * cosA - dy * sinA
            val newCenterY = pivotY + dx * sinA + dy * cosA
            val halfWidth = box.width / 2f
            val halfHeight = box.height / 2f
            token.copy(
                box = BoundingBox(
                    left = newCenterX - halfWidth,
                    top = newCenterY - halfHeight,
                    right = newCenterX + halfWidth,
                    bottom = newCenterY + halfHeight,
                ),
            )
        }
        return DeskewResult(rotated, angle)
    }
}
