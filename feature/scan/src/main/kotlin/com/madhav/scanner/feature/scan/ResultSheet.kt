package com.madhav.scanner.feature.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.madhav.scanner.core.model.Scan

/**
 * Shows the parsed receipt. An unreconciled total is flagged for the user to check rather
 * than presented as fact (DESIGN.md §7.3 step 6, §10) — this is the app's core trust signal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultSheet(
    scan: Scan,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (!scan.reconciled) {
                Text(
                    text = "Totals don't add up — check these lines",
                    color = Color.Red,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(scan.items) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val quantityPrefix = item.quantity?.let { "${it}x " } ?: ""
                        Text(text = "$quantityPrefix${item.name}")
                        Text(text = item.totalPrice?.formatMinorUnits() ?: "?")
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            scan.subtotal?.let { TotalsRow("Subtotal", it.formatMinorUnits()) }
            scan.tax?.let { TotalsRow("Tax", it.formatMinorUnits()) }
            scan.total?.let { TotalsRow("Total", it.formatMinorUnits()) }

            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun TotalsRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label)
        Text(text = value)
    }
}
