package com.madhav.scanner.core.bench

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Android-facing adapter around `PowerManager.addThermalStatusListener` (DESIGN.md §6.3),
 * feeding transitions into a plain [ThermalHistory]. The listener API is API 29+; on the
 * §1 minimum (API 26) this degrades to an always-empty history rather than crashing —
 * thermal status is a "nice to have" annotation on bench results, not a hard requirement.
 */
class ThermalWatcher(context: Context) {

    val history = ThermalHistory()

    private val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = powerManager ?: return

        val callback = PowerManager.OnThermalStatusChangedListener { status ->
            history.record(status, System.nanoTime())
        }
        pm.addThermalStatusListener(callback)
        listener = callback

        // Seed the history with the status at start, not just future transitions.
        history.record(pm.currentThermalStatus, System.nanoTime())
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = powerManager ?: return
        listener?.let(pm::removeThermalStatusListener)
        listener = null
    }
}
