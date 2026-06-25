package com.madhav.scanner.core.ml

import android.content.Context
import androidx.camera.core.ImageProxy
import com.madhav.scanner.core.bench.LatencyRecorder
import com.madhav.scanner.core.bench.Stage
import com.madhav.scanner.core.camera.DetectorGate
import com.madhav.scanner.core.model.DetectionResult
import com.madhav.scanner.core.model.ModelVariant
import org.tensorflow.lite.Interpreter

/**
 * The real per-frame detector (DESIGN.md §4.2, §D5): XNNPACK CPU delegate, 2 threads — not
 * 4, budget SoCs have 4-6 cores of which the big ones are already busy with camera, preview
 * composition, and the UI thread; oversubscribing raises p95 even when it lowers p50.
 *
 * Confined to whatever single thread calls [detect] (the analyzer's single-thread executor,
 * DESIGN.md §4.1) — the `Interpreter` instance itself is not thread-safe, and this class
 * does nothing to make it so; that's the analyzer's job, not this class's.
 */
class Detector(
    context: Context,
    variant: ModelVariant = ModelVariant.SHIPPING_DEFAULT,
    private val recorder: LatencyRecorder,
    modelRegistry: ModelRegistry = ModelRegistry(context),
) : DetectorGate, AutoCloseable {

    private val card = modelRegistry.loadModelCard(variant)

    private val interpreter: Interpreter = Interpreter(
        modelRegistry.loadModelBuffer(variant),
        Interpreter.Options().apply {
            setNumThreads(2) // DESIGN.md §D5
            setUseXNNPACK(true)
        },
    ).also { modelRegistry.assertContractMatches(card, it) }

    private val preprocessor = Preprocessor(modelInputSize = card.input.shape[1])
    private val postprocessor = Postprocessor(threshold = card.threshold.toDouble())

    // DESIGN.md §4.2: output container allocated once, reused every call to Interpreter.run().
    private val outputHeight = card.output.shape[1]
    private val outputWidth = card.output.shape[2]
    private val outputContainer = Array(1) { Array(outputHeight) { Array(outputWidth) { FloatArray(1) } } }

    override fun detect(image: ImageProxy): DetectionResult {
        val tPre = System.nanoTime()
        val input = preprocessor.process(image)
        val tInferStart = System.nanoTime()

        interpreter.run(input, outputContainer)
        val nativeNs = interpreter.lastNativeInferenceDurationNanoseconds ?: 0L
        val tInferEnd = System.nanoTime()

        val result = postprocessor.process(outputContainer[0], preprocessor.graySample, image.imageInfo.timestamp)
        val tEnd = System.nanoTime()

        // DESIGN.md §4.2: both wall-clock and native duration are recorded. The gap between
        // them is JNI + buffer-copy overhead, and on some devices it is 20-30% of the total —
        // reporting only one of them would flatter or hide where the cost actually is.
        recorder.record(Stage.PREPROCESS, tInferStart - tPre)
        recorder.record(Stage.INFERENCE_WALL, tInferEnd - tInferStart)
        recorder.record(Stage.INFERENCE_NATIVE, nativeNs)
        recorder.record(Stage.POSTPROCESS, tEnd - tInferEnd)

        return result
    }

    override fun close() {
        interpreter.close()
    }
}
