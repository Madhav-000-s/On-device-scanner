package com.madhav.scanner.feature.benchmark

data class BenchmarkUiState(
    val isRunning: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val results: List<BenchResult> = emptyList(),
)
