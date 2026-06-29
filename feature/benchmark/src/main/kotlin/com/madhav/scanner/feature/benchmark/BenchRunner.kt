package com.madhav.scanner.feature.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.madhav.scanner.core.bench.LatencyRecorder
import com.madhav.scanner.core.bench.Stage
import com.madhav.scanner.core.ml.InterpreterFactory
import com.madhav.scanner.core.ml.ModelRegistry
import com.madhav.scanner.core.ml.Postprocessor
import com.madhav.scanner.core.ml.Preprocessor
import com.madhav.scanner.core.model.Delegate
import com.madhav.scanner.core.model.ModelVariant

data class BenchConfig(val variant: ModelVariant, val delegate: Delegate, val threads: Int)

data class BenchResult(
    val config: BenchConfig,
    val coldStartNs: Long,
    val recorder: LatencyRecorder,
)

/**
 * DESIGN.md §6.1: deterministic, no camera involved. Loads 50 fixed frames from assets and
 * replays them through the real pipeline (Preprocessor -> Interpreter -> Postprocessor).
 *
 * ```
 * warmup:     20 iterations, discarded (first inference includes delegate init + XNNPACK
 *             weight repacking -- routinely 10-50x the steady-state figure)
 * cold-start: reported separately, from a fresh Interpreter, as its own metric
 * measured:   300 iterations
 * ```
 */
class BenchRunner(
    private val context: Context,
    private val modelRegistry: ModelRegistry = ModelRegistry(context),
) {
    /**
     * DESIGN.md §D5/§6.5: every combination gets measured, not assumed. Thread count is
     * only meaningful for the CPU (XNNPACK) delegate; GPU delegate dispatch doesn't use it
     * the same way, so it's held at 1 there rather than repeating three identical runs.
     */
    fun fullConfigMatrix(): List<BenchConfig> = buildList {
        for (variant in ModelVariant.entries) {
            for (threads in THREAD_COUNTS) add(BenchConfig(variant, Delegate.XNNPACK, threads))
            add(BenchConfig(variant, Delegate.GPU, threads = 1))
        }
    }

    fun run(config: BenchConfig): BenchResult {
        val frames = loadFrames()
        val preprocessor = Preprocessor()
        val postprocessor = Postprocessor()
        val card = modelRegistry.loadModelCard(config.variant)
        val outputContainer = buildOutputContainer(card.output.shape[1], card.output.shape[2])

        val recorder = LatencyRecorder(capacityPerStage = MEASURED_ITERS)
        recorder.recordColdStart(measureColdStart(config, frames[0], preprocessor, outputContainer))

        val interpreter = InterpreterFactory.create(context, modelRegistry, config.variant, config.delegate, config.threads)
        try {
            repeat(WARMUP_ITERS) { i ->
                val input = preprocessor.process(frames[i % frames.size])
                interpreter.run(input, outputContainer)
            }

            repeat(MEASURED_ITERS) { i ->
                val tPre = System.nanoTime()
                val input = preprocessor.process(frames[i % frames.size])
                val tInferStart = System.nanoTime()

                interpreter.run(input, outputContainer)
                val nativeNs = interpreter.lastNativeInferenceDurationNanoseconds ?: 0L
                val tInferEnd = System.nanoTime()

                postprocessor.process(outputContainer[0], preprocessor.graySample, frameTimestampNs = i.toLong())
                val tEnd = System.nanoTime()

                recorder.record(Stage.PREPROCESS, tInferStart - tPre)
                recorder.record(Stage.INFERENCE_WALL, tInferEnd - tInferStart)
                recorder.record(Stage.INFERENCE_NATIVE, nativeNs)
                recorder.record(Stage.POSTPROCESS, tEnd - tInferEnd)
            }
        } finally {
            interpreter.close()
        }

        return BenchResult(config, recorder.coldStartNs ?: 0L, recorder)
    }

    private fun measureColdStart(
        config: BenchConfig,
        firstFrame: Bitmap,
        preprocessor: Preprocessor,
        outputContainer: Array<Array<Array<FloatArray>>>,
    ): Long {
        val coldInterpreter = InterpreterFactory.create(context, modelRegistry, config.variant, config.delegate, config.threads)
        return try {
            val input = preprocessor.process(firstFrame)
            val start = System.nanoTime()
            coldInterpreter.run(input, outputContainer)
            System.nanoTime() - start
        } finally {
            coldInterpreter.close()
        }
    }

    private fun loadFrames(): List<Bitmap> = (0 until FRAME_COUNT).map { i ->
        context.assets.open("bench_frames/frame_%02d.jpg".format(i)).use { BitmapFactory.decodeStream(it) }
    }

    private fun buildOutputContainer(height: Int, width: Int): Array<Array<Array<FloatArray>>> =
        Array(1) { Array(height) { Array(width) { FloatArray(1) } } }

    companion object {
        const val FRAME_COUNT = 50
        const val WARMUP_ITERS = 20
        const val MEASURED_ITERS = 300
        val THREAD_COUNTS = intArrayOf(1, 2, 4)
    }
}
