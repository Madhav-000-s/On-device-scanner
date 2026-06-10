package com.madhav.scanner.core.parse

import com.madhav.scanner.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptParserTest {

    @Test
    fun `parses a clean receipt into items and reconciles`() {
        val result = ReceiptParser.parse(reconcilingReceiptTokens())

        assertEquals(2, result.items.size)

        val burger = result.items[0]
        assertEquals(2, burger.quantity)
        assertTrue(burger.name.contains("Burger"))
        assertEquals(Money.parseDecimal("9.99"), burger.unitPrice)
        assertEquals(Money.parseDecimal("19.98"), burger.totalPrice)

        val fries = result.items[1]
        assertEquals(null, fries.quantity)
        // The modifier row ("no salt") merges into the preceding item's name.
        assertTrue(fries.name.contains("Fries"))
        assertTrue(fries.name.contains("no salt"))
        assertEquals(Money.parseDecimal("4.50"), fries.totalPrice)

        assertEquals(Money.parseDecimal("24.48"), result.subtotal)
        assertEquals(Money.parseDecimal("2.00"), result.tax)
        assertEquals(Money.parseDecimal("26.48"), result.total)
        assertTrue("expected reconciled receipt to be flagged reconciled", result.reconciled)
    }

    @Test
    fun `flags a mismatched total as not reconciled rather than hiding it`() {
        val result = ReceiptParser.parse(nonReconcilingReceiptTokens())

        assertEquals(Money.parseDecimal("99.00"), result.total)
        assertFalse("a wrong printed total must not be silently accepted", result.reconciled)
    }

    @Test
    fun `empty input parses to an empty, unreconciled receipt`() {
        val result = ReceiptParser.parse(emptyList())

        assertTrue(result.items.isEmpty())
        assertFalse(result.reconciled)
    }

    @Test
    fun `receipt missing a total row is not reconciled and does not fabricate one`() {
        val tokens = listOf(
            row(0f, "2x" to 0f, "Burger" to 40f, "9.99" to 300f, "19.98" to 400f),
        ).flatten()

        val result = ReceiptParser.parse(tokens)

        assertEquals(null, result.total)
        assertFalse(result.reconciled)
    }
}
