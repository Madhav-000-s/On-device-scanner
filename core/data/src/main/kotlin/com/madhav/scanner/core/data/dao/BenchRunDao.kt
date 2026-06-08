package com.madhav.scanner.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.madhav.scanner.core.data.entity.BenchRunEntity
import com.madhav.scanner.core.data.entity.BenchSampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BenchRunDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: BenchRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSamples(samples: List<BenchSampleEntity>)

    @Transaction
    suspend fun insertRunWithSamples(run: BenchRunEntity, samples: List<BenchSampleEntity>) {
        insertRun(run)
        insertSamples(samples)
    }

    @Query("SELECT * FROM bench_runs ORDER BY startedAt DESC")
    fun observeRuns(): Flow<List<BenchRunEntity>>

    @Query("SELECT * FROM bench_samples WHERE runId = :runId ORDER BY stage, iterationIndex")
    suspend fun getSamples(runId: String): List<BenchSampleEntity>
}
