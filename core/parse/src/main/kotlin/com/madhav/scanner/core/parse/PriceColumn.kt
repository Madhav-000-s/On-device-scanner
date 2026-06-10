package com.madhav.scanner.core.parse

/** x-range (in the rectified image) that the price column occupies. */
data class PriceColumn(val left: Float, val right: Float) {
    fun contains(box: BoundingBox): Boolean = box.centerX in left..right
}

/**
 * DESIGN.md §7.3 step 3: detect the price column as the rightmost cluster of
 * currency-shaped tokens. Right-aligned numeric columns share a right edge regardless of
 * how many digits each row has, so clustering on `box.right` (not centerX) is what makes
 * this robust to "9.99" sitting next to "129.99".
 */
object PriceColumnDetector {

    // Matches "$12.99", "1,234.50", "Rs. 120", "₹1200.00", "9,99" (EU decimal comma), "120"
    // — a currency symbol/code is optional. The numeric body is either thousands-grouped
    // ("1,234.50") or plain digits with an optional 1-2 digit decimal ("1200.00", "9,99") —
    // without the plain-digit branch, a 4+ digit whole number with no thousands separator
    // (a very ordinary price) would not match at all.
    private val PRICE_PATTERN = Regex(
        """^[$€£₹¥]?\s?(?:Rs\.?|INR|USD|EUR|GBP)?\s?-?(?:\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:[.,]\d{1,2})?)$""",
        RegexOption.IGNORE_CASE,
    )

    private const val CLUSTER_GAP_FACTOR = 1.2f
    private const val MIN_ROW_SUPPORT_FRACTION = 0.2f
    private const val MIN_ROW_SUPPORT_COUNT = 2

    fun looksLikePrice(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (!trimmed.any { it.isDigit() }) return false
        return PRICE_PATTERN.matches(trimmed)
    }

    fun detect(rows: List<Row>): PriceColumn? {
        val candidates = rows.flatMap { row -> row.tokens.filter { looksLikePrice(it.text) } }
        if (candidates.isEmpty()) return null

        val averageWidth = candidates.map { it.box.width }.average().toFloat()
        val gapTolerance = averageWidth * CLUSTER_GAP_FACTOR

        val sortedByRight = candidates.sortedBy { it.box.right }
        val clusters = mutableListOf<MutableList<OcrToken>>()
        for (token in sortedByRight) {
            val last = clusters.lastOrNull()
            if (last == null || token.box.right - last.last().box.right > gapTolerance) {
                clusters += mutableListOf(token)
            } else {
                last += token
            }
        }

        val rightmost = clusters.maxByOrNull { cluster -> cluster.last().box.right } ?: return null

        val minSupport = maxOf(MIN_ROW_SUPPORT_COUNT, (rows.size * MIN_ROW_SUPPORT_FRACTION).toInt())
        if (rightmost.size < minSupport) return null

        return PriceColumn(
            left = rightmost.minOf { it.box.left },
            right = rightmost.maxOf { it.box.right },
        )
    }
}
