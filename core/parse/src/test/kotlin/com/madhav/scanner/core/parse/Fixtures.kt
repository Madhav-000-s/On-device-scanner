package com.madhav.scanner.core.parse

/** Builds a fixture token: `left` and `top` place it, width is derived from text length. */
fun token(
    text: String,
    left: Float,
    top: Float,
    height: Float = 20f,
    confidence: Float = 0.95f,
): OcrToken {
    val width = (text.length * 10f).coerceAtLeast(10f)
    return OcrToken(text, BoundingBox(left, top, left + width, top + height), confidence)
}

/** A row spec: pairs of (text, left-x); `top` is shared across the row. */
fun row(top: Float, vararg cells: Pair<String, Float>): List<OcrToken> =
    cells.map { (text, left) -> token(text, left, top) }

/**
 * A full receipt: 2x Burger @ 9.99 = 19.98, Fries (no qty) = 4.50, a modifier line under
 * Fries, Subtotal 24.48, Tax 2.00, Total 26.48 — everything reconciles exactly.
 */
fun reconcilingReceiptTokens(): List<OcrToken> = listOf(
    row(0f, "CAFE MADHAV" to 0f),
    row(40f, "2x" to 0f, "Burger" to 40f, "9.99" to 300f, "19.98" to 400f),
    row(80f, "Fries" to 40f, "4.50" to 400f),
    row(120f, "no salt" to 60f),
    row(160f, "Subtotal" to 40f, "24.48" to 400f),
    row(200f, "Tax" to 40f, "2.00" to 400f),
    row(240f, "Total" to 40f, "26.48" to 400f),
    row(280f, "Thank you" to 0f),
).flatten()

/** Same receipt but the printed Total is wrong — must NOT reconcile. */
fun nonReconcilingReceiptTokens(): List<OcrToken> = listOf(
    row(0f, "CAFE MADHAV" to 0f),
    row(40f, "2x" to 0f, "Burger" to 40f, "9.99" to 300f, "19.98" to 400f),
    row(80f, "Fries" to 40f, "4.50" to 400f),
    row(160f, "Subtotal" to 40f, "24.48" to 400f),
    row(200f, "Tax" to 40f, "2.00" to 400f),
    row(240f, "Total" to 40f, "99.00" to 400f),
).flatten()
