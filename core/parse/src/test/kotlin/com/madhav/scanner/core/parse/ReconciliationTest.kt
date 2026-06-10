package com.madhav.scanner.core.parse

import com.madhav.scanner.core.model.Money
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconciliationTest {

    @Test
    fun `reconciles exactly when items plus tax equal total`() {
        val result = Reconciliation.reconcile(
            itemTotals = listOf(Money.parseDecimal("19.98")!!, Money.parseDecimal("4.50")!!),
            subtotal = Money.parseDecimal("24.48"),
            tax = Money.parseDecimal("2.00"),
            tip = null,
            total = Money.parseDecimal("26.48"),
        )
        assertTrue(result.reconciled)
    }

    @Test
    fun `flags a one-rupee-off total as a mismatch, not within tolerance`() {
        val result = Reconciliation.reconcile(
            itemTotals = listOf(Money.parseDecimal("19.98")!!, Money.parseDecimal("4.50")!!),
            subtotal = Money.parseDecimal("24.48"),
            tax = Money.parseDecimal("2.00"),
            tip = null,
            total = Money.parseDecimal("27.48"),
        )
        assertFalse(result.reconciled)
    }

    @Test
    fun `tolerates a single cent of rounding slack`() {
        val result = Reconciliation.reconcile(
            itemTotals = listOf(Money.parseDecimal("19.98")!!, Money.parseDecimal("4.50")!!),
            subtotal = Money.parseDecimal("24.48"),
            tax = Money.parseDecimal("2.00"),
            tip = null,
            total = Money.parseDecimal("26.49"),
        )
        assertTrue(result.reconciled)
    }

    @Test
    fun `missing total is never reconciled`() {
        val result = Reconciliation.reconcile(
            itemTotals = listOf(Money.parseDecimal("19.98")!!),
            subtotal = null,
            tax = null,
            tip = null,
            total = null,
        )
        assertFalse(result.reconciled)
    }
}
