package com.madhav.scanner.feature.scan

import android.content.Context
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.madhav.scanner.core.bench.FrameClock
import com.madhav.scanner.core.bench.LatencyRecorder
import com.madhav.scanner.core.camera.BoundCamera
import com.madhav.scanner.core.camera.CameraBinder
import com.madhav.scanner.core.camera.DetectorGate
import com.madhav.scanner.core.camera.FrameAnalyzer
import com.madhav.scanner.core.camera.FrameResult
import com.madhav.scanner.core.camera.StubDetectorGate
import com.madhav.scanner.core.data.repository.ScanRepository
import com.madhav.scanner.core.ml.Detector
import com.madhav.scanner.core.model.ModelVariant
import com.madhav.scanner.core.model.Scan
import com.madhav.scanner.core.model.ScanState
import com.madhav.scanner.core.ocr.MlKitRecognizer
import com.madhav.scanner.core.ocr.PerspectiveWarp
import com.madhav.scanner.core.parse.BoundingBox
import com.madhav.scanner.core.parse.OcrToken
import com.madhav.scanner.core.parse.ReceiptParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject

private val TERMINAL_CAPTURE_STATES = setOf(ScanState.CAPTURING, ScanState.RECOGNIZE, ScanState.PARSING, ScanState.RESULT)

/**
 * Orchestrates the whole scan pipeline (DESIGN.md §4.5): camera frames drive
 * [AutoCaptureStateMachine]; reaching CAPTURING pauses analysis (§4.1 — competing for cores
 * during OCR is the worst-case latency scenario), captures a still, rectifies it, runs OCR,
 * parses it, persists it, and resumes analysis. A manual shutter can invoke [capture]
 * directly from any state — auto-capture is an accelerator, never a gate.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanRepository: ScanRepository,
) : ViewModel() {

    private val recorder = LatencyRecorder()
    private val frameClock = FrameClock(targetFps = 30)
    private val cameraBinder = CameraBinder(context)
    private val stateMachine = AutoCaptureStateMachine()
    private val recognizer = MlKitRecognizer()
    private val captureExecutor = Executors.newSingleThreadExecutor()

    private var detector: DetectorGate? = null
    private var frameAnalyzer: FrameAnalyzer? = null
    private var boundCamera: BoundCamera? = null

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    fun bindCamera(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        val gate: DetectorGate = try {
            Detector(context, variant = ModelVariant.SHIPPING_DEFAULT, recorder = recorder).also { detector = it }
        } catch (t: Throwable) {
            // Missing/corrupt model asset degrades to "always searching" rather than
            // crashing the whole screen — a manual shutter still works.
            StubDetectorGate()
        }

        val analyzer = FrameAnalyzer(gate, frameClock)
        frameAnalyzer = analyzer

        viewModelScope.launch {
            analyzer.frames.collect { result -> result?.let(::onFrameResult) }
        }

        cameraBinder.bind(lifecycleOwner, surfaceProvider, analyzer) { bound ->
            boundCamera = bound
            _uiState.update { it.copy(cameraReady = true) }
        }
    }

    private fun onFrameResult(frameResult: FrameResult) {
        if (_uiState.value.scanState in TERMINAL_CAPTURE_STATES) return

        val newState = stateMachine.onDetection(frameResult.detection)
        _uiState.update { it.copy(scanState = newState, quad = frameResult.detection.quad, guidance = guidanceFor(newState)) }

        if (newState == ScanState.CAPTURING) capture()
    }

    /** Auto-capture is an accelerator, never a gate (§4.5) — always callable directly. */
    fun manualShutter() {
        _uiState.update { it.copy(scanState = ScanState.CAPTURING, guidance = guidanceFor(ScanState.CAPTURING)) }
        capture()
    }

    private fun capture() {
        val imageCapture = boundCamera?.imageCapture ?: return
        cameraBinder.pauseAnalysis()

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(scanState = ScanState.RECOGNIZE, guidance = guidanceFor(ScanState.RECOGNIZE)) }
                val photo = imageCapture.captureBitmap(captureExecutor)

                val quad = _uiState.value.quad
                val rectified = if (quad != null) {
                    val (width, height) = PerspectiveWarp.estimateOutputSize(quad)
                    PerspectiveWarp.warp(photo, quad, width, height)
                } else {
                    photo
                }

                val elements = recognizer.recognize(rectified)
                val tokens = elements.map {
                    OcrToken(it.text, BoundingBox(it.left, it.top, it.right, it.bottom), it.confidence)
                }

                _uiState.update { it.copy(scanState = ScanState.PARSING, guidance = guidanceFor(ScanState.PARSING)) }
                val parsed = ReceiptParser.parse(tokens)

                val scan = Scan(
                    id = UUID.randomUUID().toString(),
                    createdAt = System.currentTimeMillis(),
                    imagePath = "",
                    merchant = null,
                    currency = null,
                    subtotal = parsed.subtotal,
                    tax = parsed.tax,
                    total = parsed.total,
                    reconciled = parsed.reconciled,
                    detectorVariant = ModelVariant.SHIPPING_DEFAULT.id,
                    detectorSha = "",
                    items = parsed.items,
                )
                scanRepository.saveScan(scan)

                _uiState.update { it.copy(scanState = ScanState.RESULT, guidance = guidanceFor(ScanState.RESULT), result = scan) }
            } catch (t: Throwable) {
                stateMachine.reset()
                _uiState.update { it.copy(scanState = ScanState.SEARCHING, error = t.message) }
            } finally {
                stateMachine.reset()
                frameAnalyzer?.let(cameraBinder::resumeAnalysis)
            }
        }
    }

    fun dismissResult() {
        _uiState.update { it.copy(scanState = ScanState.SEARCHING, result = null, error = null) }
    }

    override fun onCleared() {
        (detector as? AutoCloseable)?.close()
        recognizer.close()
        cameraBinder.shutdown()
        captureExecutor.shutdown()
    }
}
