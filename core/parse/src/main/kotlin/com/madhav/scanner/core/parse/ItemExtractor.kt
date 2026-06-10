package com.madhav.scanner.core.parse

import com.madhav.scanner.core.model.Money

data class ExtractedItem(
    val name: String,
    val quantity: Int?,
    val unitPrice: Money?,
    val totalPrice: Money?,
    val confidence: Float,
)

/** Strips currency symbols/codes so the remainder is a plain decimal for Money.parseDecimal. */
private fun cleanPriceText(raw: String): String =
    raw.trim().replace(Regex("""^[$€£₹¥]|Rs\.?|INR|USD|EUR|GBP""", RegexOption.IGNORE_CASE), "").trim()

private val LEADING_QUANTITY = Regex("""^(\d+)\s*[xX]$|^(\d+)[xX]$""")
private val BARE_LEADING_INTEGER = Regex("""^(\d+)$""")

/**
 * DESIGN.md §7.3 step 5: qty (leading integer / "2x"), name (middle), price (right column).
 */
object ItemExtractor {

    fun extract(row: Row, priceColumn: PriceColumn?): ExtractedItem {
        var tokens = row.tokens

        var quantity: Int? = null
        val first = tokens.firstOrNull()
        if (first != null) {
            val qtyMatch = LEADING_QUANTITY.find(first.text)
            if (qtyMatch != null) {
                quantity = (qtyMatch.groupValues[1].ifEmpty { qtyMatch.groupValues[2] }).toIntOrNull()
                tokens = tokens.drop(1)
            } else {
                val bareMatch = BARE_LEADING_INTEGER.find(first.text)
                // A bare leading integer is only a quantity if there's a name after it and
                // it isn't itself the price column (a lone number left of a price is a qty,
                // not the price itself — the price column check below prevents double-count).
                if (bareMatch != null && tokens.size > 1 && !(priceColumn?.contains(first.box) ?: false)) {
                    quantity = bareMatch.groupValues[1].toIntOrNull()
                    tokens = tokens.drop(1)
                }
            }
        }

        val priceTokens = if (priceColumn != null) {
            tokens.filter { priceColumn.contains(it.box) }
        } else {
            tokens.filter { PriceColumnDetector.looksLikePrice(it.text) }
        }
        val nameTokens = tokens - priceTokens.toSet()

        val parsedPrices = priceTokens.mapNotNull { Money.parseDecimal(cleanPriceText(it.text)) }
        val totalPrice = parsedPrices.lastOrNull()
        val unitPrice = when {
            parsedPrices.size >= 2 -> parsedPrices.first()
            totalPrice != null && quantity != null && quantity > 0 && totalPrice.cents % quantity == 0L ->
                Money(totalPrice.cents / quantity)
            else -> totalPrice
        }

        val name = nameTokens.joinToString(" ") { it.text }.trim()
        val confidence = row.tokens.map { it.confidence }.average().toFloat()

        return ExtractedItem(
            name = name,
            quantity = quantity,
            unitPrice = unitPrice,
            totalPrice = totalPrice,
            confidence = if (confidence.isNaN()) 0f else confidence,
        )
    }
}
