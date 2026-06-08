package com.madhav.scanner.core.data.repository

import com.madhav.scanner.core.data.dao.ScanDao
import com.madhav.scanner.core.data.toDomain
import com.madhav.scanner.core.data.toEntity
import com.madhav.scanner.core.model.Scan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ScanRepository @Inject constructor(
    private val scanDao: ScanDao,
) {
    fun observeScans(): Flow<List<Scan>> =
        scanDao.observeScans().map { scans -> scans.map { it.toDomain(items = emptyList()) } }

    suspend fun getScan(scanId: String): Scan? {
        val scan = scanDao.getScan(scanId) ?: return null
        val items = scanDao.getLineItems(scanId)
        return scan.toDomain(items)
    }

    suspend fun saveScan(scan: Scan) {
        scanDao.insertScanWithItems(
            scan = scan.toEntity(),
            items = scan.items.map { it.toEntity(scan.id) },
        )
    }

    suspend fun deleteScan(scan: Scan) {
        scanDao.deleteScan(scan.toEntity())
    }
}
