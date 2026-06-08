package com.madhav.scanner.core.model

/** The four quantization variants built and measured per DESIGN.md §D4. */
enum class ModelVariant(val id: String) {
    FP32("fp32"),
    FP16("fp16"),
    INT8_DYNAMIC_RANGE("int8_dr"),
    INT8_FULL("int8_full"),
    ;

    companion object {
        /** int8_full is the shipping default (DESIGN.md §D4). */
        val SHIPPING_DEFAULT = INT8_FULL

        fun fromId(id: String): ModelVariant? = entries.firstOrNull { it.id == id }
    }
}

/** Inference delegate, measured rather than assumed (DESIGN.md §D5). */
enum class Delegate {
    XNNPACK,
    GPU,
}
