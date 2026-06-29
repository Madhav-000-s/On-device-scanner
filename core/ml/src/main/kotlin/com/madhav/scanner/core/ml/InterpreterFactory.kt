package com.madhav.scanner.core.ml

import android.content.Context
import com.madhav.scanner.core.model.Delegate
import com.madhav.scanner.core.model.ModelVariant
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate

/**
 * Builds an [Interpreter] for a given (variant, delegate, threads) combination. Shared by
 * [Detector] (fixed at the §D5 shipping default: XNNPACK, 2 threads) and the benchmark
 * harness's config matrix (DESIGN.md §6.5: every combination gets measured, not assumed).
 */
object InterpreterFactory {

    fun create(
        context: Context,
        modelRegistry: ModelRegistry,
        variant: ModelVariant,
        delegate: Delegate,
        threads: Int,
    ): Interpreter {
        val options = Interpreter.Options().apply {
            setNumThreads(threads)
            when (delegate) {
                Delegate.XNNPACK -> setUseXNNPACK(true)
                Delegate.GPU -> addDelegate(GpuDelegate())
            }
        }
        val card = modelRegistry.loadModelCard(variant)
        return Interpreter(modelRegistry.loadModelBuffer(variant), options).also {
            modelRegistry.assertContractMatches(card, it)
        }
    }
}
