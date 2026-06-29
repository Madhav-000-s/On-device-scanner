package com.madhav.scanner.feature.history

import android.content.Context
import android.content.Intent
import com.madhav.scanner.core.model.Scan

fun shareScan(context: Context, scan: Scan) {
    val body = buildString {
        appendLine(scan.merchant ?: "Receipt")
        scan.items.forEach { item ->
            val quantityPrefix = item.quantity?.let { "${it}x " } ?: ""
            appendLine("$quantityPrefix${item.name}: ${item.totalPrice?.formatMinorUnits() ?: "?"}")
        }
        scan.total?.let { appendLine("Total: ${it.formatMinorUnits()}") }
        if (!scan.reconciled) appendLine("(totals did not reconcile — check original receipt)")
    }

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, body)
    }
    context.startActivity(Intent.createChooser(intent, "Share receipt"))
}
