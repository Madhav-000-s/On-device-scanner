package com.madhav.scanner.feature.benchmark

import android.content.Context
import android.os.Build
import com.madhav.scanner.core.bench.Stage
import com.madhav.scanner.core.data.entity.BenchRunEntity
import com.madhav.scanner.core.data.entity.BenchSampleEntity
import com.madhav.scanner.core.data.repository.BenchRunRepository
import com.madhav.scanner.core.ml.ModelRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * Runs the DESIGN.md §6.5 config matrix "unattended" and persists every run (§6.6: raw
 * samples kept, not just percentiles) tagged with the device/config context that produced it.
 */
@HiltViewModel
class BenchmarkViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val benchRunRepository: BenchRunRepository,
) : ViewModel() {

    private val runner = BenchRunner(context, ModelRegistry(context))

    private val _uiState = MutableStateFlow(BenchmarkUiState())
    val uiState: StateFlow<BenchmarkUiState> = _uiState.asStateFlow()

    fun runFullMatrix() {
        if (_uiState.value.isRunning) return

        viewModelScope.launch {
            val configs = runner.fullConfigMatrix()
            _uiState.update { it.copy(isRunning = true, completed = 0, total = configs.size, results = emptyList()) }

            val results = mutableListOf<BenchResult>()
            for ((index, config) in configs.withIndex()) {
                val result = withContext(Dispatchers.Default) { runner.run(config) }
                results += result
                persistResult(result)
                _uiState.update { it.copy(completed = index + 1, results = results.toList()) }
            }

            _uiState.update { it.copy(isRunning = false) }
        }
    }

    private suspend fun persistResult(result: BenchResult) {
        val runId = UUID.randomUUID().toString()
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "unknown"

        val runEntity = BenchRunEntity(
            id = runId,
            startedAt = System.currentTimeMillis(),
            variant = result.config.variant.id,
            delegate = result.config.delegate.name,
            threads = result.config.threads,
            socModel = socModel,
            deviceModel = Build.MODEL,
            androidSdk = Build.VERSION.SDK_INT,
            thermalAtStart = -1, // DESIGN.md §6.3 thermal tagging wires in via ThermalWatcher at the app layer
            thermalAtEnd = -1,
            warmupIters = BenchRunner.WARMUP_ITERS,
            measuredIters = BenchRunner.MEASURED_ITERS,
            coldStartNs = result.coldStartNs,
            statsJson = BenchResultExporter.toJson(listOf(result)),
        )

        val samples = Stage.entries.flatMap { stage ->
            result.recorder.rawSamples(stage).mapIndexed { index, durationNs ->
                BenchSampleEntity(runId = runId, stage = stage.name, iterationIndex = index, durationNs = durationNs)
            }
        }

        benchRunRepository.saveRun(runEntity, samples)
    }

    fun exportJson(): String = BenchResultExporter.toJson(_uiState.value.results)
    fun exportCsv(): String = BenchResultExporter.toCsv(_uiState.value.results)
}
