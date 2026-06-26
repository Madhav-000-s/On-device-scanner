package com.madhav.scanner.feature.scan

import com.madhav.scanner.core.model.Quad
import com.madhav.scanner.core.model.Scan
import com.madhav.scanner.core.model.ScanState

data class ScanUiState(
    val cameraReady: Boolean = false,
    val scanState: ScanState = ScanState.SEARCHING,
    val quad: Quad? = null,
    val guidance: String = "Point the camera at a receipt or menu",
    val result: Scan? = null,
    val error: String? = null,
)

fun guidanceFor(state: ScanState): String = when (state) {
    ScanState.SEARCHING -> "Point the camera at a receipt or menu"
    ScanState.ALIGNING -> "Hold steady, aligning..."
    ScanState.STABLE -> "Hold still..."
    ScanState.CAPTURING -> "Capturing..."
    ScanState.RECOGNIZE -> "Reading text..."
    ScanState.PARSING -> "Parsing items..."
    ScanState.RESULT -> "Done"
}
