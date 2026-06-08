package com.madhav.scanner.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Raw per-iteration latency samples for a bench run (DESIGN.md §6.6: "Raw samples are kept,
 * not just percentiles — you will want to re-derive a different statistic later, and you
 * will not want to re-run the matrix"). `stage` is one of preprocess/inference-wall/
 * inference-native/postprocess (DESIGN.md §4.2).
 */
@Entity(
    tableName = "bench_samples",
    foreignKeys = [
        ForeignKey(
            entity = BenchRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("runId")],
)
data class BenchSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val stage: String,
    val iterationIndex: Int,
    val durationNs: Long,
)
