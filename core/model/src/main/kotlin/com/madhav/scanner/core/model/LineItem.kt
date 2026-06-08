package com.madhav.scanner.core.model

/** Domain representation of a parsed line item (DESIGN.md §8), independent of Room. */
data class LineItem(
    val id: String,
    val ordinal: Int,
    val rowType: RowType,
    val name: String,
    val quantity: Int?,
    val unitPrice: Money?,
    val totalPrice: Money?,
    val ocrConfidence: Float,
    val userEdited: Boolean = false,
)

/** A fully parsed scan: its line items plus the reconciliation outcome (DESIGN.md §7.3 step 6). */
data class Scan(
    val id: String,
    val createdAt: Long,
    val imagePath: String,
    val merchant: String?,
    val currency: String?,
    val subtotal: Money?,
    val tax: Money?,
    val total: Money?,
    val reconciled: Boolean,
    val detectorVariant: String,
    val detectorSha: String,
    val items: List<LineItem>,
)
