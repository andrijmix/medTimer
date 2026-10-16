package com.futsch1.medtimer.feature.mcp.domain

import com.futsch1.medtimer.core.domain.model.ReminderEvent.ReminderStatus
import com.futsch1.medtimer.feature.mcp.event
import com.futsch1.medtimer.feature.mcp.medicine
import com.futsch1.medtimer.feature.mcp.reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class MedicineIndexTest {

    private val at = Instant.parse("2026-10-10T08:00:00Z")

    private val index = MedicineIndex(
        listOf(
            medicine(1, "Vitamin X 500 mg", listOf(reminder(10, 1))),
            medicine(2, "Medicine A", listOf(reminder(20, 2))),
            medicine(3, "Twin"),
            medicine(4, "Twin")
        )
    )

    @Test
    fun `should find the medicine through the reminder of the event`() {
        assertEquals(2, index.medicineIdOf(event(1, 20, "Whatever the name was", ReminderStatus.TAKEN, at)))
    }

    @Test
    fun `should fall back to the name when the reminder no longer exists`() {
        assertEquals(1, index.medicineIdOf(event(1, 99, "Vitamin X 500 mg", ReminderStatus.TAKEN, at)))
    }

    @Test
    fun `should find a dose that was logged by hand by its name`() {
        assertEquals(2, index.medicineIdOf(event(1, -1, "Medicine A", ReminderStatus.TAKEN, at)))
    }

    @Test
    fun `should ignore the cycle counter that is part of the stored name`() {
        assertEquals(1, index.medicineIdOf(event(1, 99, "Vitamin X 500 mg (2/5)", ReminderStatus.TAKEN, at)))
    }

    @Test
    fun `should not guess when several medicines have the name`() {
        assertNull(index.medicineIdOf(event(1, 99, "Twin", ReminderStatus.TAKEN, at)))
    }

    @Test
    fun `should return null for an unknown medicine`() {
        assertNull(index.medicineIdOf(event(1, 99, "Deleted medicine", ReminderStatus.TAKEN, at)))
    }
}
