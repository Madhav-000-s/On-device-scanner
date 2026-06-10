package com.madhav.scanner.core.parse

import com.madhav.scanner.core.model.RowType

/**
 * Multilingual keyword lexicon for row classification (DESIGN.md §7.3 step 4). Covers
 * English plus a handful of languages the training corpora touch (DESIGN.md §5.2: CORD is
 * Indonesian receipts). Extend here first if a real receipt misclassifies — this is meant to
 * plateau before a model ever becomes necessary (DESIGN.md §7.3, §10).
 */
object Lexicon {

    val SUBTOTAL: Set<String> = setOf(
        "subtotal", "sub total", "sub-total", "jumlah sementara",
    )

    val TAX: Set<String> = setOf(
        "tax", "vat", "gst", "sales tax", "service tax", "ppn", "pajak",
    )

    val TIP: Set<String> = setOf(
        "tip", "gratuity", "service charge", "svc chg",
    )

    val TOTAL: Set<String> = setOf(
        "total", "grand total", "amount due", "balance due", "total due",
        "jumlah", "jumlah total", "total bayar",
    )

    val FOOTER: Set<String> = setOf(
        "thank you", "thanks for", "please come again", "visit again",
        "customer copy", "terima kasih", "have a nice day",
    )

    val HEADER: Set<String> = setOf(
        "receipt", "invoice", "order #", "order no", "table", "server", "cashier",
        "struk", "no. meja",
    )

    fun containsAny(text: String, keywords: Set<String>): Boolean {
        val normalized = text.lowercase()
        return keywords.any { normalized.contains(it) }
    }
}
