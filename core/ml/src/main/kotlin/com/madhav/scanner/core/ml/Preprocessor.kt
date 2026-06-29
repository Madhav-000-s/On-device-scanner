package com.madhav.scanner.core.ml

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp
import org.tensorflow.lite.support.image.ops.Rot90Op
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DESIGN.md §4.3: RGBA_8888 analysis frame -> center-crop to square -> resize to the model's
 * 256x256 input -> derotate -> normalize to [-1, 1].
 *
 * "wrap plane[0].buffer as TensorImage (no copy)" (§4.3) is the aspiration; the TFLite
 * Support library's public API only accepts a `Bitmap` or an already-built `TensorBuffer`,
 * so one copy into [reusableBitmap] is unavoidable here. That single copy is still far
 * cheaper than hand-rolling YUV->RGB (already avoided entirely by taking RGBA_8888 output
 * from CameraX itself, per §D6) — the point this section of the design doc is making is
 * "don't add a second, redundant conversion," not "zero copies are literally achievable
 * through this library."
 *
 * Every buffer below — [reusableBitmap] and [outputBuffer] — is allocated once in the
 * constructor and reused every frame (§4.3: a per-frame 256x256x3 float allocation is 768 KB
 * of garbage at 30 FPS, i.e. GC pauses that show up directly as p95 latency spikes).
 */
class Preprocessor(
    analysisWidth: Int = 640,
    analysisHeight: Int = 480,
    private val modelInputSize: Int = 256,
) {
    private val cropSize = minOf(analysisWidth, analysisHeight)
    private val cropLeft = (analysisWidth - cropSize) / 2
    private val cropTop = (analysisHeight - cropSize) / 2

    private val reusableBitmap = Bitmap.createBitmap(analysisWidth, analysisHeight, Bitmap.Config.ARGB_8888)

    // Float32 NHWC: 1 * modelInputSize * modelInputSize * 3 channels * 4 bytes/float.
    val outputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(modelInputSize * modelInputSize * 3 * 4)
        .order(ByteOrder.nativeOrder())

    /**
     * Grayscale, model-input-resolution sample for [Postprocessor]'s sharpness check
     * (DESIGN.md §4.4: "variance of Laplacian ... on the downscaled Y channel"). The
     * design doc's `postprocessor.process(outputMask, rotationDegrees)` snippet has no
     * image data to compute sharpness from, so this exposes the one piece the postprocessor
     * actually needs, reused every frame like every other buffer here.
     */
    val graySample: Mat = Mat(modelInputSize, modelInputSize, org.opencv.core.CvType.CV_8UC1)
    private val rgbaSample = Mat(cropSize, cropSize, org.opencv.core.CvType.CV_8UC4)

    private fun buildImageProcessor(rotationDegrees: Int): ImageProcessor = ImageProcessor.Builder()
        .add(ResizeWithCropOrPadOp(cropSize, cropSize))
        .add(ResizeOp(modelInputSize, modelInputSize, ResizeOp.ResizeMethod.BILINEAR))
        .add(Rot90Op(rotationDegrees / 90))
        .add(NormalizeOp(127.5f, 127.5f)) // DESIGN.md §D4/§5.1: input normalized to [-1, 1]
        .build()

    /** Returns [outputBuffer], rewound and freshly filled — never a new allocation. */
    fun process(image: ImageProxy): ByteBuffer {
        reusableBitmap.copyPixelsFromBuffer(image.planes[0].buffer)
        return processReusableBitmap(image.imageInfo.rotationDegrees)
    }

    /**
     * Same pipeline, for a source that's already a [Bitmap] rather than a live [ImageProxy]
     * (DESIGN.md §6.1's `BenchRunner`: "replays them through the real pipeline" using fixed
     * asset frames, not a camera feed). `source` must already be [analysisWidth]x[analysisHeight].
     */
    fun process(source: Bitmap, rotationDegrees: Int = 0): ByteBuffer {
        val canvas = android.graphics.Canvas(reusableBitmap)
        canvas.drawBitmap(source, 0f, 0f, null)
        return processReusableBitmap(rotationDegrees)
    }

    private fun processReusableBitmap(rotationDegrees: Int): ByteBuffer {
        val tensorImage = TensorImage.fromBitmap(reusableBitmap)
        val processed = buildImageProcessor(rotationDegrees).process(tensorImage)

        outputBuffer.rewind()
        outputBuffer.put(processed.buffer)
        outputBuffer.rewind()

        updateGraySample()

        return outputBuffer
    }

    private fun updateGraySample() {
        val cropped = Bitmap.createBitmap(reusableBitmap, cropLeft, cropTop, cropSize, cropSize)
        Utils.bitmapToMat(cropped, rgbaSample)
        Imgproc.cvtColor(rgbaSample, rgbaSample, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.resize(rgbaSample, graySample, graySample.size())
    }
}
