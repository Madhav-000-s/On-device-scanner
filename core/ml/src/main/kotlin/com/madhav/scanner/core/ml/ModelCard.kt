package com.madhav.scanner.core.ml

import com.madhav.scanner.core.model.ModelVariant
import org.json.JSONObject

data class TensorContract(
    val shape: List<Int>,
    val dtype: String,
)

/**
 * Parsed model_card.json (DESIGN.md §5.3): "the contract between the model repo and the
 * app." [ModelRegistry] asserts the loaded interpreter's real tensors match this before the
 * first inference — a silently changed model contract is the kind of bug that eats an
 * evening.
 */
data class ModelCard(
    val variant: ModelVariant,
    val sha256: String,
    val bytes: Long,
    val input: TensorContract,
    val output: TensorContract,
    val threshold: Float,
    val trainedCommit: String?,
) {
    companion object {
        fun parse(json: String): ModelCard {
            val root = JSONObject(json)
            val variantId = root.getString("variant")
            val variant = ModelVariant.fromId(variantId)
                ?: error("unknown model variant in model_card.json: $variantId")

            return ModelCard(
                variant = variant,
                sha256 = root.getString("sha256"),
                bytes = root.getLong("bytes"),
                input = root.getJSONObject("input").toTensorContract(),
                output = root.getJSONObject("output").toTensorContract(),
                threshold = root.getDouble("threshold").toFloat(),
                trainedCommit = root.optString("trained_commit", null.toString()).takeIf { it != "null" },
            )
        }

        private fun JSONObject.toTensorContract(): TensorContract {
            val shapeArray = getJSONArray("shape")
            val shape = (0 until shapeArray.length()).map { shapeArray.getInt(it) }
            return TensorContract(shape = shape, dtype = getString("dtype"))
        }
    }
}
