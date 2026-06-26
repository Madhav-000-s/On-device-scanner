package com.madhav.scanner.core.ocr

import android.graphics.Bitmap
import com.madhav.scanner.core.model.Quad
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * DESIGN.md §7.1: "getPerspectiveTransform + warpPerspective to a rectified image with the
 * receipt's estimated aspect ratio. Rectified input measurably improves ML Kit's accuracy on
 * angled shots -- it's not cosmetic."
 */
object PerspectiveWarp {

    /** Estimates the output size from the quad's own edge lengths rather than a fixed size,
     * so a tall narrow receipt and a wide menu don't get the same target aspect ratio.
     */
    fun estimateOutputSize(quad: Quad): Pair<Int, Int> {
        val topWidth = distance(quad.topLeft, quad.topRight)
        val bottomWidth = distance(quad.bottomLeft, quad.bottomRight)
        val leftHeight = distance(quad.topLeft, quad.bottomLeft)
        val rightHeight = distance(quad.topRight, quad.bottomRight)

        val width = max(topWidth, bottomWidth).roundToInt().coerceAtLeast(1)
        val height = max(leftHeight, rightHeight).roundToInt().coerceAtLeast(1)
        return width to height
    }

    fun warp(source: Bitmap, quad: Quad, outputWidth: Int, outputHeight: Int): Bitmap {
        val sourceMat = Mat()
        Utils.bitmapToMat(source, sourceMat)

        val srcPoints = MatOfPoint2f(
            Point(quad.topLeft.x.toDouble(), quad.topLeft.y.toDouble()),
            Point(quad.topRight.x.toDouble(), quad.topRight.y.toDouble()),
            Point(quad.bottomRight.x.toDouble(), quad.bottomRight.y.toDouble()),
            Point(quad.bottomLeft.x.toDouble(), quad.bottomLeft.y.toDouble()),
        )
        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((outputWidth - 1).toDouble(), 0.0),
            Point((outputWidth - 1).toDouble(), (outputHeight - 1).toDouble()),
            Point(0.0, (outputHeight - 1).toDouble()),
        )

        val transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val warped = Mat(outputHeight, outputWidth, CvType.CV_8UC4)
        Imgproc.warpPerspective(sourceMat, warped, transform, Size(outputWidth.toDouble(), outputHeight.toDouble()))

        val result = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warped, result)

        sourceMat.release()
        warped.release()
        transform.release()
        srcPoints.release()
        dstPoints.release()

        return result
    }

    private fun distance(a: com.madhav.scanner.core.model.Point, b: com.madhav.scanner.core.model.Point): Float =
        hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
}
