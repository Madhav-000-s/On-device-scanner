package com.madhav.scanner.feature.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.madhav.scanner.core.model.Quad
import com.madhav.scanner.core.model.ScanState

/**
 * Draws the detector's quad, scaled from the analyzer's 640x480 coordinate space (DESIGN.md
 * §D7) into this composable's own size, colored by [ScanState] so alignment quality is
 * visible without reading the guidance text.
 */
@Composable
fun OverlayCanvas(
    quad: Quad?,
    scanState: ScanState,
    analysisWidth: Float = 640f,
    analysisHeight: Float = 480f,
    modifier: Modifier = Modifier,
) {
    val color = when (scanState) {
        ScanState.SEARCHING -> Color.White.copy(alpha = 0.6f)
        ScanState.ALIGNING -> Color.Yellow
        ScanState.STABLE, ScanState.CAPTURING -> Color.Green
        ScanState.RECOGNIZE, ScanState.PARSING, ScanState.RESULT -> Color.Green
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (quad == null) return@Canvas

        val scaleX = size.width / analysisWidth
        val scaleY = size.height / analysisHeight

        fun scaled(point: com.madhav.scanner.core.model.Point) = Offset(point.x * scaleX, point.y * scaleY)

        val path = Path().apply {
            val points = quad.points().map(::scaled)
            moveTo(points[0].x, points[0].y)
            for (point in points.drop(1)) lineTo(point.x, point.y)
            close()
        }

        drawPath(path = path, color = color, style = Stroke(width = 6f))
    }
}
