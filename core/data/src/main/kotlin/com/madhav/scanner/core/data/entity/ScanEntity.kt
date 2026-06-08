package com.madhav.scanner.core.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** DESIGN.md §8. Money fields are Long cents — never Double, never Float. */
@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val imagePath: String,
    val merchant: String?,
    val currency: String?,
    val subtotalCents: Long?,
    val taxCents: Long?,
    val totalCents: Long?,
    val reconciled: Boolean,
    val detectorVariant: String,
    val detectorSha: String,
)
