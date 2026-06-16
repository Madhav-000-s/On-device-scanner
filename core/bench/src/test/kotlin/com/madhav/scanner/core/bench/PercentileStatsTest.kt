package com.madhav.scanner.core.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PercentileStatsTest {

    @Test
    fun `single sample - every percentile equals that sample`() {
        val stats = PercentileStats.from(longArrayOf(42L))

        assertEquals(1, stats.count)
        assertEquals(42L, stats.min)
        assertEquals(42L, stats.max)
        assertEquals(42L, stats.p50)
        assertEquals(42L, stats.p90)
        assertEquals(42L, stats.p95)
        assertEquals(42L, stats.p99)
        assertEquals(0.0, stats.stddev, 0.0001)
    }

    @Test
    fun `all-identical samples - zero stddev, every percentile equal`() {
        val stats = PercentileStats.from(LongArray(50) { 10L })

        assertEquals(10.0, stats.mean, 0.0001)
        assertEquals(0.0, stats.stddev, 0.0001)
        assertEquals(10L, stats.p50)
        assertEquals(10L, stats.p99)
    }

    @Test
    fun `computes percentiles from an unsorted input without mutating caller's array`() {
        val input = longArrayOf(100, 1, 50, 2, 99, 3, 98, 4, 97, 5)
        val original = input.copyOf()

        val stats = PercentileStats.from(input)

        assertEquals(original.toList(), input.toList())
        assertEquals(1L, stats.min)
        assertEquals(100L, stats.max)
    }

    @Test
    fun `p99 of 100 evenly spaced samples is the 99th value, not an interpolation`() {
        // 1..100 — nearest-rank p99 of 100 samples is index 98 (0-based) = value 99.
        val samples = LongArray(100) { (it + 1).toLong() }
        val stats = PercentileStats.from(samples)

        assertEquals(50L, stats.p50)
        assertEquals(90L, stats.p90)
        assertEquals(95L, stats.p95)
        assertEquals(99L, stats.p99)
        assertEquals(100L, stats.max)
    }

    @Test
    fun `rejects an empty sample set rather than returning garbage`() {
        assertThrows(IllegalArgumentException::class.java) {
            PercentileStats.from(LongArray(0))
        }
    }
}
