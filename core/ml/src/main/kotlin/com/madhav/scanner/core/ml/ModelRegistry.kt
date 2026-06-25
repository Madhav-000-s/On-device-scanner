package com.madhav.scanner.core.ml

import android.content.Context
import com.madhav.scanner.core.model.ModelVariant
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Resolves the bundled model assets and enforces the model_card.json contract (DESIGN.md
 * §5.3). Models are loaded via a memory-mapped [FileChannel] rather than reading bytes into
 * a heap array — this only works because `:core:ml`'s `androidResources.noCompress` keeps
 * the .tflite files uncompressed in the APK (DESIGN.md §3); a compressed asset can't be
 * mapped and would cost a full decompress + heap copy at startup instead.
 */
class ModelRegistry(private val context: Context) {

    fun loadModelCard(variant: ModelVariant): ModelCard {
        val json = context.assets.open(cardAssetPath(variant)).bufferedReader().use { it.readText() }
        return ModelCard.parse(json)
    }

    fun loadModelBuffer(variant: ModelVariant): MappedByteBuffer {
        context.assets.openFd(modelAssetPath(variant)).use { fd ->
            FileInputStream(fd.fileDescriptor).use { input ->
                return input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    /**
     * Asserts the interpreter's real tensors match [card] before the first inference
     * (DESIGN.md §5.3: "A silently changed model contract is the kind of bug that eats an
     * evening"). Throws [IllegalStateException] on any mismatch rather than proceeding.
     */
    fun assertContractMatches(card: ModelCard, interpreter: Interpreter) {
        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)

        val actualInputShape = inputTensor.shape().toList()
        val actualOutputShape = outputTensor.shape().toList()

        check(actualInputShape == card.input.shape) {
            "model_card input shape ${card.input.shape} does not match the interpreter's " +
                "actual shape $actualInputShape for variant ${card.variant.id}"
        }
        check(actualOutputShape == card.output.shape) {
            "model_card output shape ${card.output.shape} does not match the interpreter's " +
                "actual shape $actualOutputShape for variant ${card.variant.id}"
        }
        check(inputTensor.dataType().name.equals(card.input.dtype, ignoreCase = true)) {
            "model_card input dtype ${card.input.dtype} does not match the interpreter's " +
                "actual dtype ${inputTensor.dataType()} for variant ${card.variant.id}"
        }
        check(outputTensor.dataType().name.equals(card.output.dtype, ignoreCase = true)) {
            "model_card output dtype ${card.output.dtype} does not match the interpreter's " +
                "actual dtype ${outputTensor.dataType()} for variant ${card.variant.id}"
        }
    }

    private fun modelAssetPath(variant: ModelVariant): String = "models/detector_${variant.id}.tflite"
    private fun cardAssetPath(variant: ModelVariant): String = "models/model_card_${variant.id}.json"
}
