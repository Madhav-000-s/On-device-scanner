package com.madhav.scanner.core.parse

/** A cluster of tokens on the same visual line, left-to-right reading order. */
data class Row(val tokens: List<OcrToken>) {
    val top: Float get() = tokens.minOf { it.box.top }
    val bottom: Float get() = tokens.maxOf { it.box.bottom }
    val centerY: Float get() = (top + bottom) / 2f
    val text: String get() = tokens.joinToString(" ") { it.text }
}

/**
 * DESIGN.md §7.3 step 2: cluster elements into rows by y-overlap, tolerance = 0.6 × median
 * height. Tokens are walked in top-order; a token joins the current row if the vertical gap
 * between it and the row's running band is within tolerance, otherwise it starts a new row.
 * This tolerates the ascender/descender height variance within one printed line while still
 * splitting genuinely separate lines.
 */
object RowGrouping {

    private const val OVERLAP_TOLERANCE_FACTOR = 0.6f

    fun groupIntoRows(tokens: List<OcrToken>): List<Row> {
        if (tokens.isEmpty()) return emptyList()

        val medianHeight = tokens.map { it.box.height }.sorted().let { it[it.size / 2] }
        val tolerance = medianHeight * OVERLAP_TOLERANCE_FACTOR

        val sorted = tokens.sortedBy { it.box.top }
        val rows = mutableListOf<MutableList<OcrToken>>()
        var bandTop = Float.NaN
        var bandBottom = Float.NaN

        for (token in sorted) {
            val gap = token.box.top - bandBottom
            if (rows.isEmpty() || gap > tolerance) {
                rows += mutableListOf(token)
                bandTop = token.box.top
                bandBottom = token.box.bottom
            } else {
                rows.last() += token
                bandTop = minOf(bandTop, token.box.top)
                bandBottom = maxOf(bandBottom, token.box.bottom)
            }
        }

        return rows
            .map { rowTokens -> Row(rowTokens.sortedBy { it.box.centerX }) }
            .sortedBy { it.centerY }
    }
}
