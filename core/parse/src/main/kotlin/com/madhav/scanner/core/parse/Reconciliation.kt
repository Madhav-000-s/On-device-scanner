package com.madhav.scanner.core.parse

import com.madhav.scanner.core.model.Money
import kotlin.math.abs

data class ReconciliationResult(
    val reconciled: Boolean,
    val expectedTotal: Money?,
    val actualTotal: Money?,
)

/**
 * DESIGN.md §7.3 step 6: Σ items + tax + tip ≟ total. A mismatch is surfaced to the user as
 * "check these lines" rather than silently presented as fact (§7.3, §10) — this is the
 * quality signal that makes the app trustworthy, not a cosmetic check.
 */
object Reconciliation {

    /** One cent of rounding slack — real receipts round each line, which can accumulate. */
    private const val TOLERANCE_CENTS = 1L

    fun reconcile(
        itemTotals: List<Money>,
        subtotal: Money?,
        tax: Money?,
        tip: Money?,
        total: Money?,
    ): ReconciliationResult {
        val itemsSum = itemTotals.fold(Money.ZERO) { acc, m -> acc + m }
        val base = subtotal ?: itemsSum
        val expected = base + (tax ?: Money.ZERO) + (tip ?: Money.ZERO)

        if (total == null) {
            return ReconciliationResult(reconciled = false, expectedTotal = expected, actualTotal = null)
        }

        val matches = abs(expected.cents - total.cents) <= TOLERANCE_CENTS
        return ReconciliationResult(reconciled = matches, expectedTotal = expected, actualTotal = total)
    }
}
