package com.madhav.scanner.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.madhav.scanner.core.data.entity.LineItemEntity
import com.madhav.scanner.core.data.entity.ScanEntity
import kotlinx.coroutines.flow.Flow

data class ScanWithItems(
    val scan: ScanEntity,
    val items: List<LineItemEntity>,
)

@Dao
interface ScanDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScan(scan: ScanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLineItems(items: List<LineItemEntity>)

    @Transaction
    suspend fun insertScanWithItems(scan: ScanEntity, items: List<LineItemEntity>) {
        insertScan(scan)
        insertLineItems(items)
    }

    @Update
    suspend fun updateLineItem(item: LineItemEntity)

    @Delete
    suspend fun deleteScan(scan: ScanEntity)

    @Query("SELECT * FROM scans ORDER BY createdAt DESC")
    fun observeScans(): Flow<List<ScanEntity>>

    @Query("SELECT * FROM scans WHERE id = :scanId")
    suspend fun getScan(scanId: String): ScanEntity?

    @Query("SELECT * FROM line_items WHERE scanId = :scanId ORDER BY ordinal ASC")
    suspend fun getLineItems(scanId: String): List<LineItemEntity>
}
