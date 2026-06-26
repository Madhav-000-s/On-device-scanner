package com.madhav.scanner.core.ocr

/**
 * One recognized word-like unit (DESIGN.md §7.2: "blocks -> lines -> elements, each with a
 * bounding box and confidence"). `confidence` is fixed at 1.0 -- ML Kit's on-device Text
 * Recognition API (`com.google.mlkit:text-recognition`) does not expose a per-element
 * confidence score in its public surface, unlike some cloud OCR APIs. This is a real API
 * limitation, not a shortcut: if per-element confidence is needed later, ML Kit's Document
 * Scanner / Cloud Vision would have to replace this library, which is out of scope (§1).
 */
data class RecognizedElement(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float = 1.0f,
)
