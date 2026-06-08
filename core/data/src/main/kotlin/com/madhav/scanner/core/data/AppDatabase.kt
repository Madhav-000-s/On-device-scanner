package com.madhav.scanner.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.madhav.scanner.core.data.dao.BenchRunDao
import com.madhav.scanner.core.data.dao.ScanDao
import com.madhav.scanner.core.data.entity.BenchRunEntity
import com.madhav.scanner.core.data.entity.BenchSampleEntity
import com.madhav.scanner.core.data.entity.LineItemEntity
import com.madhav.scanner.core.data.entity.ScanEntity

@Database(
    entities = [
        ScanEntity::class,
        LineItemEntity::class,
        BenchRunEntity::class,
        BenchSampleEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao
    abstract fun benchRunDao(): BenchRunDao

    companion object {
        const val DATABASE_NAME = "scanner.db"
    }
}
