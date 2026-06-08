package com.madhav.scanner.core.data.repository

import com.madhav.scanner.core.data.dao.BenchRunDao
import com.madhav.scanner.core.data.entity.BenchRunEntity
import com.madhav.scanner.core.data.entity.BenchSampleEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class BenchRunRepository @Inject constructor(
    private val benchRunDao: BenchRunDao,
) {
    fun observeRuns(): Flow<List<BenchRunEntity>> = benchRunDao.observeRuns()

    suspend fun saveRun(run: BenchRunEntity, samples: List<BenchSampleEntity>) {
        benchRunDao.insertRunWithSamples(run, samples)
    }

    suspend fun getSamples(runId: String): List<BenchSampleEntity> = benchRunDao.getSamples(runId)
}
