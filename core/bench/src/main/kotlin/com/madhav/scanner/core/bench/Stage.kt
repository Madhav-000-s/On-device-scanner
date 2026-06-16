package com.madhav.scanner.core.bench

/** The four timed stages of the per-frame pipeline (DESIGN.md §4.2). */
enum class Stage {
    PREPROCESS,
    INFERENCE_WALL,
    INFERENCE_NATIVE,
    POSTPROCESS,
}
