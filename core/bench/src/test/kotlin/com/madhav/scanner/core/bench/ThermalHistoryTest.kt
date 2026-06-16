package com.madhav.scanner.core.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThermalHistoryTest {

    @Test
    fun `empty history has no current status`() {
        assertNull(ThermalHistory().currentStatus())
    }

    @Test
    fun `current status is the most recently recorded transition`() {
        val history = ThermalHistory()
        history.record(status = 0, timestampNs = 0L)
        history.record(status = 2, timestampNs = 100L)

        assertEquals(2, history.currentStatus())
    }

    @Test
    fun `statusAt returns the status in effect at a past timestamp, not the latest`() {
        val history = ThermalHistory()
        history.record(status = 0, timestampNs = 0L)
        history.record(status = 1, timestampNs = 100L)
        history.record(status = 3, timestampNs = 200L)

        assertEquals(0, history.statusAt(50L))
        assertEquals(1, history.statusAt(150L))
        assertEquals(3, history.statusAt(250L))
    }

    @Test
    fun `statusAt before any transition is null`() {
        val history = ThermalHistory()
        history.record(status = 1, timestampNs = 100L)

        assertNull(history.statusAt(50L))
    }

    @Test
    fun `reset clears all transitions`() {
        val history = ThermalHistory()
        history.record(status = 1, timestampNs = 0L)

        history.reset()

        assertNull(history.currentStatus())
        assertEquals(0, history.transitions.size)
    }
}
