package com.futsch1.medtimer.feature.mcp.domain

import com.futsch1.medtimer.core.domain.model.ReminderEvent
import com.futsch1.medtimer.core.domain.model.ReminderEvent.ReminderStatus
import com.futsch1.medtimer.core.domain.model.ReminderType
import com.futsch1.medtimer.feature.mcp.event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class AdherenceCalculatorTest {

    private val now = Instant.parse("2026-10-10T12:00:00Z")
    private val past = Instant.parse("2026-10-09T08:00:00Z")

    // Reminder 10 belongs to medicine 1 and reminder 20 to medicine 2; anything else is unknown.
    private val medicineIdOf: (ReminderEvent) -> Int? = { event ->
        when (event.reminderId) {
            10 -> 1
            20 -> 2
            else -> null
        }
    }

    private fun calculate(vararg events: ReminderEvent) = AdherenceCalculator.calculate(events.toList(), now, medicineIdOf)

    @Test
    fun `should count taken skipped and unanswered doses`() {
        val report = calculate(
            event(1, 10, "Vitamin X 500 mg", ReminderStatus.TAKEN, past),
            event(2, 10, "Vitamin X 500 mg", ReminderStatus.TAKEN, past.plusSeconds(3600)),
            event(3, 10, "Vitamin X 500 mg", ReminderStatus.SKIPPED, past.plusSeconds(7200)),
            event(4, 10, "Vitamin X 500 mg", ReminderStatus.RAISED, past.plusSeconds(10800))
        )

        assertEquals(AdherenceCounts(taken = 2, skipped = 1, unanswered = 1), report.overall)
        assertEquals(50.0, report.overall.adherencePercent)
    }

    @Test
    fun `should round the percentage to one decimal`() {
        val report = calculate(
            event(1, 10, "Vitamin X 500 mg", ReminderStatus.TAKEN, past),
            event(2, 10, "Vitamin X 500 mg", ReminderStatus.TAKEN, past.plusSeconds(3600)),
            event(3, 10, "Vitamin X 500 mg", ReminderStatus.SKIPPED, past.plusSeconds(7200))
        )

        assertEquals(66.7, report.overall.adherencePercent)
    }

    @Test
    fun `should not count a reminder that is raised later than now`() {
        val report = calculate(event(1, 10, "Vitamin X 500 mg", ReminderStatus.RAISED, now.plusSeconds(3600)))

        assertEquals(AdherenceCounts(), report.overall)
        assertEquals(emptyList<MedicineAdherence>(), report.perMedicine)
    }

    @Test
    fun `should ignore reminders about stock and expiry`() {
        val report = calculate(
            event(1, 10, "Vitamin X 500 mg", ReminderStatus.ACKNOWLEDGED, past, ReminderType.OUT_OF_STOCK),
            event(2, 10, "Vitamin X 500 mg", ReminderStatus.ACKNOWLEDGED, past, ReminderType.EXPIRATION_DATE)
        )

        assertEquals(AdherenceCounts(), report.overall)
    }

    @Test
    fun `should count a dose logged by hand as extra and keep it out of the percentage`() {
        val report = calculate(
            event(1, 10, "Vitamin X 500 mg", ReminderStatus.TAKEN, past),
            event(2, -1, "Vitamin X 500 mg", ReminderStatus.TAKEN, past.plusSeconds(3600))
        )

        assertEquals(AdherenceCounts(taken = 1, extraDoses = 1), report.overall)
        assertEquals(100.0, report.overall.adherencePercent)
    }

    @Test
    fun `should have no percentage when there were only extra doses`() {
        val report = calculate(event(1, -1, "Vitamin X 500 mg", ReminderStatus.TAKEN, past))

        assertEquals(1, report.overall.extraDoses)
        assertNull(report.overall.adherencePercent)
    }

    @Test
    fun `should not count a refill as a dose`() {
        val report = calculate(event(1, -1, "Vitamin X 500 mg", ReminderStatus.ACKNOWLEDGED, past, ReminderType.REFILL))

        assertEquals(AdherenceCounts(), report.overall)
    }

    @Test
    fun `should report each medicine separately and sort them by name`() {
        val report = calculate(
            event(1, 20, "Medicine A", ReminderStatus.TAKEN, past),
            event(2, 10, "Vitamin X 500 mg", ReminderStatus.SKIPPED, past),
            event(3, 10, "Vitamin X 500 mg", ReminderStatus.TAKEN, past.plusSeconds(3600))
        )

        assertEquals(listOf("Medicine A", "Vitamin X 500 mg"), report.perMedicine.map { it.medicineName })
        assertEquals(listOf(2, 1), report.perMedicine.map { it.medicineId })
        assertEquals(AdherenceCounts(taken = 1), report.perMedicine[0].counts)
        assertEquals(AdherenceCounts(taken = 1, skipped = 1), report.perMedicine[1].counts)
        assertEquals(AdherenceCounts(taken = 2, skipped = 1), report.overall)
    }

    @Test
    fun `should group events of an unknown medicine by name`() {
        val report = calculate(
            event(1, 99, "Deleted medicine (1/3)", ReminderStatus.TAKEN, past),
            event(2, 98, "Deleted medicine (2/3)", ReminderStatus.SKIPPED, past.plusSeconds(3600))
        )

        assertEquals(1, report.perMedicine.size)
        assertNull(report.perMedicine.single().medicineId)
        assertEquals("Deleted medicine", report.perMedicine.single().medicineName)
        assertEquals(AdherenceCounts(taken = 1, skipped = 1), report.perMedicine.single().counts)
    }

    @Test
    fun `should name a medicine after its newest event`() {
        val report = calculate(
            event(1, 10, "Old name", ReminderStatus.TAKEN, past),
            event(2, 10, "New name", ReminderStatus.TAKEN, past.plusSeconds(3600))
        )

        assertEquals("New name", report.perMedicine.single().medicineName)
    }
}
