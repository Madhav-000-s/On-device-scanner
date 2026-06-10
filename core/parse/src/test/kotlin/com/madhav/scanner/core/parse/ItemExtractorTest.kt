package com.madhav.scanner.core.parse

import com.madhav.scanner.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemExtractorTest {

    // Wide enough to cover both the unit-price and total-price token positions used below.
    private val priceColumn = PriceColumn(left = 280f, right = 460f)

    @Test
    fun `extracts 2x quantity form`() {
        val r = Row(row(0f, "2x" to 0f, "Burger" to 40f, "9.99" to 300f, "19.98" to 400f))
        val item = ItemExtractor.extract(r, priceColumn)

        assertEquals(2, item.quantity)
        assertEquals("Burger", item.name)
        assertEquals(Money.parseDecimal("9.99"), item.unitPrice)
        assertEquals(Money.parseDecimal("19.98"), item.totalPrice)
    }

    @Test
    fun `extracts bare leading integer quantity form`() {
        val r = Row(row(0f, "3" to 0f, "Soda" to 30f, "6.00" to 400f))
        val item = ItemExtractor.extract(r, priceColumn)

        assertEquals(3, item.quantity)
        assertEquals("Soda", item.name)
        // total 6.00 / qty 3 = unit 2.00, derived since only one price token was present.
        assertEquals(Money.parseDecimal("2.00"), item.unitPrice)
        assertEquals(Money.parseDecimal("6.00"), item.totalPrice)
    }

    @Test
    fun `defaults to no explicit quantity when none is printed`() {
        val r = Row(row(0f, "Fries" to 40f, "4.50" to 400f))
        val item = ItemExtractor.extract(r, priceColumn)

        assertEquals(null, item.quantity)
        assertEquals(Money.parseDecimal("4.50"), item.totalPrice)
        assertEquals(Money.parseDecimal("4.50"), item.unitPrice)
    }
}
