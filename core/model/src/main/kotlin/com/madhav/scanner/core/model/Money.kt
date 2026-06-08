package com.madhav.scanner.core.model

/**
 * Money is always integer cents. DESIGN.md §8: the moment a price touches a float,
 * 19.99 becomes 19.989999 and totals stop reconciling.
 */
@JvmInline
value class Money(val cents: Long) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(cents + other.cents)
    operator fun minus(other: Money): Money = Money(cents - other.cents)
    operator fun times(factor: Int): Money = Money(cents * factor)

    override fun compareTo(other: Money): Int = cents.compareTo(other.cents)

    fun formatMinorUnits(): String {
        val negative = cents < 0
        val abs = kotlin.math.abs(cents)
        val whole = abs / 100
        val fraction = abs % 100
        val sign = if (negative) "-" else ""
        return "$sign$whole.${fraction.toString().padStart(2, '0')}"
    }

    companion object {
        val ZERO = Money(0)

        /**
         * Parses a decimal string ("19.99", "1,234.50", "19") straight to integer cents
         * without ever constructing a Float/Double.
         */
        fun parseDecimal(raw: String): Money? {
            val cleaned = raw.trim().replace(",", "")
            val negative = cleaned.startsWith("-")
            val unsigned = cleaned.removePrefix("-")
            if (unsigned.isEmpty() || !unsigned.all { it.isDigit() || it == '.' }) return null

            val parts = unsigned.split(".")
            if (parts.isEmpty() || parts.size > 2) return null

            val wholePart = parts[0].ifEmpty { "0" }
            val fractionPart = when {
                parts.size == 1 -> "00"
                parts[1].length == 1 -> parts[1] + "0"
                parts[1].length == 2 -> parts[1]
                else -> parts[1].substring(0, 2)
            }
            if (!wholePart.all { it.isDigit() } || !fractionPart.all { it.isDigit() }) return null

            val whole = wholePart.toLongOrNull() ?: return null
            val fraction = fractionPart.toLongOrNull() ?: return null
            val cents = whole * 100 + fraction
            return Money(if (negative) -cents else cents)
        }
    }
}

fun Iterable<Money>.sum(): Money = fold(Money.ZERO) { acc, m -> acc + m }
