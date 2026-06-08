package com.madhav.scanner.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun `parses plain decimal to cents`() {
        assertEquals(1999L, Money.parseDecimal("19.99")!!.cents)
    }

    @Test
    fun `parses whole number as zero cents fraction`() {
        assertEquals(1900L, Money.parseDecimal("19")!!.cents)
    }

    @Test
    fun `parses single fraction digit as tens of cents`() {
        assertEquals(1950L, Money.parseDecimal("19.5")!!.cents)
    }

    @Test
    fun `strips thousands separators`() {
        assertEquals(123450L, Money.parseDecimal("1,234.50")!!.cents)
    }

    @Test
    fun `parses negative amounts`() {
        assertEquals(-1999L, Money.parseDecimal("-19.99")!!.cents)
    }

    @Test
    fun `rejects non-numeric input`() {
        assertNull(Money.parseDecimal("free"))
    }

    @Test
    fun `rejects multiple decimal points`() {
        assertNull(Money.parseDecimal("19.9.9"))
    }

    @Test
    fun `never touches floating point - repeated addition stays exact`() {
        // The classic 19.99 -> 19.989999... failure mode this type exists to prevent.
        var total = Money.ZERO
        repeat(3) { total += Money.parseDecimal("19.99")!! }
        assertEquals(5997L, total.cents)
        assertEquals("59.97", total.formatMinorUnits())
    }

    @Test
    fun `formats cents back to decimal string`() {
        assertEquals("19.99", Money(1999).formatMinorUnits())
        assertEquals("19.05", Money(1905).formatMinorUnits())
        assertEquals("-19.99", Money(-1999).formatMinorUnits())
    }

    @Test
    fun `sum extension reduces a list without float drift`() {
        val items = listOf(Money(1999), Money(999), Money(150))
        assertEquals(3148L, items.sum().cents)
    }
}
