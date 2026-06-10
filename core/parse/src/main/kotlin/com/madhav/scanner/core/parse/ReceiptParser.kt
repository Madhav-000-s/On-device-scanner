package com.madhav.scanner.core.parse

import com.madhav.scanner.core.model.LineItem
import com.madhav.scanner.core.model.Money
import com.madhav.scanner.core.model.RowType
import java.util.UUID

data class ParsedReceipt(
    val items: List<LineItem>,
    val subtotal: Money?,
    val tax: Money?,
    val tip: Money?,
    val total: Money?,
    val reconciled: Boolean,
)

/**
 * Orchestrates the full DESIGN.md §7.3 pipeline: deskew -> group -> price column -> classify
 * -> extract -> reconcile.
 */
object ReceiptParser {

    fun parse(tokens: List<OcrToken>): ParsedReceipt {
        if (tokens.isEmpty()) {
            return ParsedReceipt(emptyList(), null, null, null, null, reconciled = false)
        }

        val deskewed = Deskew.deskew(tokens).tokens
        val rows = RowGrouping.groupIntoRows(deskewed)
        val priceColumn = PriceColumnDetector.detect(rows)
        val rowTypes = RowClassifier.classify(rows, priceColumn)

        var subtotal: Money? = null
        var tax: Money? = null
        var tip: Money? = null
        var total: Money? = null
        val items = mutableListOf<LineItem>()

        rows.forEachIndexed { index, row ->
            when (val type = rowTypes[index]) {
                RowType.SUBTOTAL -> subtotal = extractSummaryValue(row)
                RowType.TAX -> tax = extractSummaryValue(row)
                RowType.TIP -> tip = extractSummaryValue(row)
                RowType.TOTAL -> total = extractSummaryValue(row)
                RowType.ITEM -> {
                    val extracted = ItemExtractor.extract(row, priceColumn)
                    items += LineItem(
                        id = UUID.randomUUID().toString(),
                        ordinal = items.size,
                        rowType = RowType.ITEM,
                        name = extracted.name,
                        quantity = extracted.quantity,
                        unitPrice = extracted.unitPrice,
                        totalPrice = extracted.totalPrice,
                        ocrConfidence = extracted.confidence,
                    )
                }
                RowType.MODIFIER -> {
                    // A modifier line belongs to the item immediately above it.
                    val previous = items.removeLastOrNull()
                    if (previous != null) {
                        items += previous.copy(name = "${previous.name} (${row.text.trim()})")
                    }
                }
                RowType.HEADER, RowType.FOOTER -> Unit
            }
        }

        val reconciliation = Reconciliation.reconcile(
            itemTotals = items.mapNotNull { it.totalPrice },
            subtotal = subtotal,
            tax = tax,
            tip = tip,
            total = total,
        )

        return ParsedReceipt(
            items = items,
            subtotal = subtotal,
            tax = tax,
            tip = tip,
            // Never fabricate a total the receipt didn't show — a missing TOTAL row means
            // reconciled=false and the UI asks the user to check the lines, not a computed
            // stand-in presented as read fact (DESIGN.md §7.3 step 6, §10).
            total = total,
            reconciled = reconciliation.reconciled,
        )
    }

    private fun extractSummaryValue(row: Row): Money? {
        val priceTokens = row.tokens.filter { PriceColumnDetector.looksLikePrice(it.text) }
        val token = priceTokens.lastOrNull() ?: return null
        val cleaned = token.text.trim().replace(Regex("""^[$€£₹¥]|Rs\.?|INR|USD|EUR|GBP""", RegexOption.IGNORE_CASE), "")
        return Money.parseDecimal(cleaned.trim())
    }
}
