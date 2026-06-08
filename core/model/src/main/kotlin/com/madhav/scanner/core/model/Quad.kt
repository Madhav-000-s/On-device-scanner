package com.madhav.scanner.core.model

data class Point(val x: Float, val y: Float)

/**
 * A four-corner document quad, derived from the segmentation mask (DESIGN.md §D2):
 * threshold -> largest connected component -> contour -> minAreaRect/4-point approx.
 * Corners are ordered top-left, top-right, bottom-right, bottom-left.
 */
data class Quad(
    val topLeft: Point,
    val topRight: Point,
    val bottomRight: Point,
    val bottomLeft: Point,
) {
    fun points(): List<Point> = listOf(topLeft, topRight, bottomRight, bottomLeft)
}
