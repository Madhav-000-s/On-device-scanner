package com.madhav.scanner.core.bench

data class ThermalTransition(val timestampNs: Long, val status: Int)

/**
 * Pure record of thermal status transitions (DESIGN.md §6.3), decoupled from
 * `PowerManager` so the transition bookkeeping is unit-testable without a device.
 * [ThermalWatcher] is the Android-facing adapter that feeds this from real callbacks.
 */
class ThermalHistory {

    private val _transitions = mutableListOf<ThermalTransition>()
    val transitions: List<ThermalTransition> get() = _transitions

    fun record(status: Int, timestampNs: Long) {
        _transitions += ThermalTransition(timestampNs, status)
    }

    fun currentStatus(): Int? = _transitions.lastOrNull()?.status

    /** The status in effect at [timestampNs] — the last transition at or before it. */
    fun statusAt(timestampNs: Long): Int? =
        _transitions.lastOrNull { it.timestampNs <= timestampNs }?.status

    fun reset() {
        _transitions.clear()
    }
}
