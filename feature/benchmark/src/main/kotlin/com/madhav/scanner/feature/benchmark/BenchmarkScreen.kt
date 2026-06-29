package com.madhav.scanner.feature.benchmark

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun BenchmarkScreen(
    modifier: Modifier = Modifier,
    viewModel: BenchmarkViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { context.contentResolver.openOutputStream(it)?.use { stream -> stream.write(viewModel.exportJson().toByteArray()) } }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        uri?.let { context.contentResolver.openOutputStream(it)?.use { stream -> stream.write(viewModel.exportCsv().toByteArray()) } }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Benchmark (DESIGN.md §6)")

        Button(
            onClick = viewModel::runFullMatrix,
            enabled = !uiState.isRunning,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text(if (uiState.isRunning) "Running ${uiState.completed}/${uiState.total}..." else "Run full config matrix")
        }

        if (uiState.isRunning && uiState.total > 0) {
            LinearProgressIndicator(
                progress = { uiState.completed.toFloat() / uiState.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = { jsonLauncher.launch("bench_results.json") }, enabled = uiState.results.isNotEmpty()) {
                Text("Export JSON")
            }
            Button(onClick = { csvLauncher.launch("bench_results.csv") }, enabled = uiState.results.isNotEmpty()) {
                Text("Export CSV")
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(uiState.results) { result ->
                val p95 = result.recorder.stats(com.madhav.scanner.core.bench.Stage.INFERENCE_WALL)?.p95?.div(1_000_000.0)
                Text(
                    "${result.config.variant.id} / ${result.config.delegate.name} / ${result.config.threads}t" +
                        (p95?.let { " — inference p95 %.1fms".format(it) } ?: ""),
                )
            }
        }
    }
}
