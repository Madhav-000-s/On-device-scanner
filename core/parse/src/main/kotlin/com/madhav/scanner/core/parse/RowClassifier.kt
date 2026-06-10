package com.madhav.scanner.core.parse

import com.madhav.scanner.core.model.RowType

/**
 * DESIGN.md §7.3 step 4: classify each row via keyword matching, position in the document,
 * currency-token presence, and indentation. Keyword-matched summary lines (subtotal/tax/
 * tip/total/footer) take priority since they're unambiguous when present. Everything else
 * falls back to position + price-column occupancy: rows before the first priced row are
 * HEADER, priced rows are ITEM, unpriced rows indented past the item name's left margin are
 * MODIFIER (e.g. "no onions" under a burger line).
 */
object RowClassifier {

    private const val MODIFIER_INDENT_THRESHOLD = 12f

    fun classify(
        rows: List<Row>,
        priceColumn: PriceColumn?,
    ): List<RowType> {
        if (rows.isEmpty()) return emptyList()

        val itemLeftMargin = rows
            .filter { row -> priceColumn != null && row.tokens.any { priceColumn.contains(it.box) } }
            .minOfOrNull { it.tokens.first().box.left }

        val firstPricedRowIndex = rows.indexOfFirst { row ->
            priceColumn != null && row.tokens.any { priceColumn.contains(it.box) }
        }

        return rows.mapIndexed { index, row ->
            val text = row.text
            when {
                Lexicon.containsAny(text, Lexicon.SUBTOTAL) -> RowType.SUBTOTAL
                Lexicon.containsAny(text, Lexicon.TAX) -> RowType.TAX
                Lexicon.containsAny(text, Lexicon.TIP) -> RowType.TIP
                Lexicon.containsAny(text, Lexicon.TOTAL) -> RowType.TOTAL
                Lexicon.containsAny(text, Lexicon.FOOTER) -> RowType.FOOTER
                Lexicon.containsAny(text, Lexicon.HEADER) -> RowType.HEADER
                firstPricedRowIndex >= 0 && index < firstPricedRowIndex -> RowType.HEADER
                priceColumn != null && row.tokens.any { priceColumn.contains(it.box) } -> RowType.ITEM
                itemLeftMargin != null && row.tokens.first().box.left > itemLeftMargin + MODIFIER_INDENT_THRESHOLD ->
                    RowType.MODIFIER
                firstPricedRowIndex in 0 until index -> RowType.FOOTER
                else -> RowType.ITEM
            }
        }
    }
}
