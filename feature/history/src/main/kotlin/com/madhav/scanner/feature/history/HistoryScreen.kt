package com.madhav.scanner.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.madhav.scanner.core.model.Scan
import java.text.DateFormat
import java.util.Date

/** Empty and error states per DESIGN.md Phase 5. */
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val scans by viewModel.scans.collectAsStateWithLifecycle()
    val selectedScan by viewModel.selectedScan.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(modifier = modifier.fillMaxSize()) {
        if (scans.isEmpty()) {
            Text(
                text = "No scans yet — capture a receipt to see it here",
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(scans, key = { it.id }) { scan ->
                    ScanRow(
                        scan = scan,
                        onClick = { viewModel.selectScan(scan.id) },
                        onDelete = { viewModel.deleteScan(scan) },
                    )
                    HorizontalDivider()
                }
            }
        }

        selectedScan?.let { scan ->
            HistoryDetailSheet(
                scan = scan,
                onDismiss = viewModel::clearSelection,
                onDelete = { viewModel.deleteScan(scan) },
                onShare = { shareScan(context, scan) },
            )
        }
    }
}

@Composable
private fun ScanRow(scan: Scan, onClick: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        headlineContent = { Text(scan.merchant ?: "Receipt") },
        supportingContent = {
            Column {
                Text(DateFormat.getDateTimeInstance().format(Date(scan.createdAt)))
                if (!scan.reconciled) Text("Totals don't reconcile — check this scan")
            }
        },
        trailingContent = {
            Row {
                Text(scan.total?.formatMinorUnits() ?: "")
                TextButton(onClick = onDelete) {
                    Text("Delete")
                }
            }
        },
    )
}
