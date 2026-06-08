package com.madhav.scanner.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "line_items",
    foreignKeys = [
        ForeignKey(
            entity = ScanEntity::class,
            parentColumns = ["id"],
            childColumns = ["scanId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("scanId")],
)
data class LineItemEntity(
    @PrimaryKey val id: String,
    val scanId: String,
    val ordinal: Int,
    val name: String,
    val quantity: Int?,
    val unitPriceCents: Long?,
    val totalPriceCents: Long?,
    val ocrConfidence: Float,
    val userEdited: Boolean,
)
