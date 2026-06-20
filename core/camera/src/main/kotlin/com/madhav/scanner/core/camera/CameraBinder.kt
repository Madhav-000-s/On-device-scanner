package com.madhav.scanner.core.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

data class BoundCamera(
    val camera: Camera,
    val imageAnalysis: ImageAnalysis,
    val imageCapture: ImageCapture,
)

/**
 * Wires Preview + ImageAnalysis + ImageCapture with the DESIGN.md §D7 resolution split:
 * analysis targets 640x480 (downscaled to 256x256 for the model anyway — requesting more is
 * "the single most common way to wreck an ML camera app", per the design doc), capture
 * targets the sensor's maximum since OCR needs the pixels. Analysis output format is
 * RGBA_8888 (§D6) so the YUV->RGB conversion cost lands inside CameraX's native code rather
 * than in measured inference time.
 */
class CameraBinder(private val context: Context) {

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var boundCamera: BoundCamera? = null

    fun bind(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        analyzer: ImageAnalysis.Analyzer,
        onBound: (BoundCamera) -> Unit,
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(surfaceProvider)
                }

                val analysisResolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisResolutionSelector)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .apply { setAnalyzer(analysisExecutor, analyzer) }

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                    imageCapture,
                )

                val bound = BoundCamera(camera, imageAnalysis, imageCapture)
                boundCamera = bound
                onBound(bound)
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /**
     * Pauses analysis for the duration of capture + OCR (DESIGN.md §4.1): competing for
     * cores during OCR is the worst-case latency scenario and there's no reason to allow it.
     */
    fun pauseAnalysis() {
        boundCamera?.imageAnalysis?.clearAnalyzer()
    }

    fun resumeAnalysis(analyzer: ImageAnalysis.Analyzer) {
        boundCamera?.imageAnalysis?.setAnalyzer(analysisExecutor, analyzer)
    }

    fun shutdown() {
        analysisExecutor.shutdown()
    }
}
