package com.madhav.scanner.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.madhav.scanner.core.data.repository.ScanRepository
import com.madhav.scanner.core.model.Scan
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val scanRepository: ScanRepository,
) : ViewModel() {

    /** List rows only — [ScanRepository.observeScans] intentionally omits line items for a
     * lightweight list view; the detail screen loads a scan's items on demand via [selectScan].
     */
    val scans: StateFlow<List<Scan>> = scanRepository.observeScans()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedScan = MutableStateFlow<Scan?>(null)
    val selectedScan: StateFlow<Scan?> = _selectedScan.asStateFlow()

    fun selectScan(scanId: String) {
        viewModelScope.launch {
            _selectedScan.value = scanRepository.getScan(scanId)
        }
    }

    fun clearSelection() {
        _selectedScan.value = null
    }

    fun deleteScan(scan: Scan) {
        viewModelScope.launch {
            scanRepository.deleteScan(scan)
            if (_selectedScan.value?.id == scan.id) _selectedScan.value = null
        }
    }
}
