package com.madhav.scanner.core.ml

import com.madhav.scanner.core.model.DetectionResult
import com.madhav.scanner.core.model.Point as ModelPoint
import com.madhav.scanner.core.model.Quad
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

/**
 * DESIGN.md §4.4: threshold at tau=0.5 -> largest contour (reject if < 8% of frame) ->
 * 4-point approx (falling back to minAreaRect) -> sharpness via variance of Laplacian on the
 * downscaled Y channel. Corners are ordered top-left/top-right/bottom-right/bottom-left so
 * downstream code (the overlay, the capture-time perspective warp) never has to guess.
 */
class Postprocessor(
    private val threshold: Double = 0.5,
    private val minAreaFraction: Double = 0.08,
) {
    private val binaryMask = Mat()

    fun process(outputMask: Array<Array<FloatArray>>, graySample: org.opencv.core.Mat, frameTimestampNs: Long): DetectionResult {
        val size = outputMask.size
        if (binaryMask.empty() || binaryMask.rows() != size) {
            binaryMask.create(size, size, CvType.CV_8UC1)
        }
        for (y in 0 until size) {
            for (x in 0 until size) {
                binaryMask.put(y, x, if (outputMask[y][x][0] >= threshold) 255.0 else 0.0)
            }
        }

        val confidence = averageConfidence(outputMask)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binaryMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()

        val totalArea = (size * size).toDouble()
        val largest = contours.maxByOrNull { Imgproc.contourArea(it) }

        if (largest == null || Imgproc.contourArea(largest) < minAreaFraction * totalArea) {
            contours.forEach { it.release() }
            return DetectionResult(quad = null, confidence = confidence, coverage = 0f, sharpness = 0f, frameTimestampNs = frameTimestampNs)
        }

        val coverage = (Imgproc.contourArea(largest) / totalArea).toFloat()
        val contour2f = MatOfPoint2f(*largest.toArray())
        val perimeter = Imgproc.arcLength(contour2f, true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(contour2f, approx, 0.02 * perimeter, true)

        val rawPoints: Array<Point> = if (approx.toArray().size == 4) {
            approx.toArray()
        } else {
            val rect = Imgproc.minAreaRect(contour2f)
            val boxPoints = Mat()
            Imgproc.boxPoints(rect, boxPoints)
            val points = Array(4) { i -> Point(boxPoints.get(i, 0)[0], boxPoints.get(i, 1)[0]) }
            boxPoints.release()
            points
        }

        val ordered = orderPoints(rawPoints)
        val quad = Quad(
            topLeft = ordered[0].toModelPoint(),
            topRight = ordered[1].toModelPoint(),
            bottomRight = ordered[2].toModelPoint(),
            bottomLeft = ordered[3].toModelPoint(),
        )

        val sharpness = computeSharpness(graySample)

        contour2f.release()
        approx.release()
        contours.forEach { it.release() }

        return DetectionResult(quad = quad, confidence = confidence, coverage = coverage, sharpness = sharpness, frameTimestampNs = frameTimestampNs)
    }

    private fun averageConfidence(outputMask: Array<Array<FloatArray>>): Float {
        var sum = 0.0
        var count = 0
        for (row in outputMask) {
            for (pixel in row) {
                sum += pixel[0]
                count++
            }
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    /** DESIGN.md §4.4: "sharpness: variance of Laplacian ... on the downscaled Y channel." */
    private fun computeSharpness(gray: org.opencv.core.Mat): Float {
        val laplacian = Mat()
        Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
        val mean = org.opencv.core.MatOfDouble()
        val stddev = org.opencv.core.MatOfDouble()
        Core.meanStdDev(laplacian, mean, stddev)
        val variance = stddev.toArray().getOrElse(0) { 0.0 }.let { it * it }
        laplacian.release()
        return variance.toFloat()
    }

    private fun orderPoints(points: Array<Point>): List<Point> {
        val bySum = points.sortedBy { it.x + it.y }
        val topLeft = bySum.first()
        val bottomRight = bySum.last()
        val byDiff = points.sortedBy { it.y - it.x }
        val topRight = byDiff.first()
        val bottomLeft = byDiff.last()
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun Point.toModelPoint(): ModelPoint = ModelPoint(x.toFloat(), y.toFloat())
}
