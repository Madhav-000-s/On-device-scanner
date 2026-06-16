package com.madhav.scanner.core.bench

import kotlin.math.sqrt

/**
 * DESIGN.md §6.1: percentiles from raw sorted samples, never a running approximation —
 * 300 samples is 2.4 KB of longs, there is no reason to approximate.
 */
data class PercentileStats(
    val count: Int,
    val mean: Double,
    val stddev: Double,
    val min: Long,
    val max: Long,
    val p50: Long,
    val p90: Long,
    val p95: Long,
    val p99: Long,
) {
    companion object {
        /**
         * Nearest-rank percentile on a sorted copy of [samples]. `samples` need not already
         * be sorted — this method owns the sort so callers never have to reason about it.
         */
        fun from(samples: LongArray): PercentileStats {
            require(samples.isNotEmpty()) { "cannot compute percentiles over zero samples" }

            val sorted = samples.sortedArray()
            val mean = sorted.average()
            val variance = sorted.sumOf { (it - mean) * (it - mean) } / sorted.size
            val stddev = sqrt(variance)

            return PercentileStats(
                count = sorted.size,
                mean = mean,
                stddev = stddev,
                min = sorted.first(),
                max = sorted.last(),
                p50 = percentile(sorted, 0.50),
                p90 = percentile(sorted, 0.90),
                p95 = percentile(sorted, 0.95),
                p99 = percentile(sorted, 0.99),
            )
        }

        /** Nearest-rank method: index = ceil(p * n) - 1, clamped into range. */
        private fun percentile(sorted: LongArray, p: Double): Long {
            val rank = kotlin.math.ceil(p * sorted.size).toInt()
            val index = (rank - 1).coerceIn(0, sorted.size - 1)
            return sorted[index]
        }
    }
}
