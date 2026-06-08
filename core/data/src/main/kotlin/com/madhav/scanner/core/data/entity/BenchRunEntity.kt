package com.madhav.scanner.core.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** DESIGN.md §8 / §6.6. `statsJson` holds the full percentile block for every stage. */
@Entity(tableName = "bench_runs")
data class BenchRunEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val variant: String,
    val delegate: String,
    val threads: Int,
    val socModel: String,
    val deviceModel: String,
    val androidSdk: Int,
    val thermalAtStart: Int,
    val thermalAtEnd: Int,
    val warmupIters: Int,
    val measuredIters: Int,
    val coldStartNs: Long,
    val statsJson: String,
)
