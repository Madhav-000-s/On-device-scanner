package com.madhav.scanner.core.parse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceColumnTest {

    @Test
    fun `recognizes plain decimal prices`() {
        assertTrue(PriceColumnDetector.looksLikePrice("9.99"))
        assertTrue(PriceColumnDetector.looksLikePrice("129.99"))
    }

    @Test
    fun `recognizes multi-currency symbol-prefixed prices`() {
        assertTrue(PriceColumnDetector.looksLikePrice("$9.99"))
        assertTrue(PriceColumnDetector.looksLikePrice("€9,99"))
        assertTrue(PriceColumnDetector.looksLikePrice("£12.50"))
        assertTrue(PriceColumnDetector.looksLikePrice("₹120"))
        assertTrue(PriceColumnDetector.looksLikePrice("Rs. 120"))
        assertTrue(PriceColumnDetector.looksLikePrice("INR 1200.00"))
    }

    @Test
    fun `recognizes thousands separators`() {
        assertTrue(PriceColumnDetector.looksLikePrice("1,234.50"))
    }

    @Test
    fun `rejects non-price tokens`() {
        assertFalse(PriceColumnDetector.looksLikePrice("Burger"))
        assertFalse(PriceColumnDetector.looksLikePrice("2x"))
        assertFalse(PriceColumnDetector.looksLikePrice(""))
    }

    @Test
    fun `detects the rightmost aligned column across rows of varying digit count`() {
        val rows = listOf(
            row(0f, "Burger" to 40f, "9.99" to 400f),
            row(40f, "Extra large combo meal" to 40f, "129.99" to 390f),
            row(80f, "Fries" to 40f, "4.50" to 400f),
        ).map { Row(it) }

        val column = PriceColumnDetector.detect(rows)

        requireNotNull(column)
        // Both "9.99" (right ~ 440) and "129.99" (right ~ 450) should cluster together.
        assertTrue(column.right >= 440f)
    }

    @Test
    fun `returns null when there is no consistent price column`() {
        val rows = listOf(
            row(0f, "just some text" to 0f),
            row(40f, "more text here" to 0f),
        ).map { Row(it) }

        assertTrue(PriceColumnDetector.detect(rows) == null)
    }
}
