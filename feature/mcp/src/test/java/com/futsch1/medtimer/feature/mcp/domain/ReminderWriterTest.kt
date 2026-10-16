package com.futsch1.medtimer.feature.mcp.domain

import com.futsch1.medtimer.core.domain.model.Reminder
import com.futsch1.medtimer.core.domain.model.ReminderTime
import com.futsch1.medtimer.core.domain.model.ReminderType
import com.futsch1.medtimer.feature.mcp.FakeDatabase
import com.futsch1.medtimer.feature.mcp.fixedTime
import com.futsch1.medtimer.feature.mcp.medicine
import com.futsch1.medtimer.feature.mcp.reminder
import com.futsch1.medtimer.feature.mcp.tools.ToolArgumentException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertFailsWith

class ReminderWriterTest {

    private val db = FakeDatabase()
    private val writer = ReminderWriter(db.reminderRepository, db.medicineRepository, fixedTime())

    private val now = Instant.parse("2026-10-10T12:00:00Z")

    private fun setUpVitamin() = db.add(medicine(1, "Vitamin X 500 mg"))

    private suspend fun create(type: ReminderType, changes: ReminderChanges, linkedReminderId: Int? = null, medicineId: Int = 1) =
        writer.create(medicineId, type, linkedReminderId, changes).value

    @Test
    fun `should create a time based reminder starting from what the app's dialogs start from`() = runTest {
        setUpVitamin()

        val created = create(ReminderType.TIME_BASED, ReminderChanges(amount = "2", time = LocalTime.of(8, 30), days = listOf(DayOfWeek.MONDAY)))

        assertEquals(ReminderType.TIME_BASED, created.reminderType)
        assertEquals(1, created.medicineRelId)
        assertEquals("2", created.amount)
        assertEquals(LocalTime.of(8, 30), created.time.getLocalTime())
        assertEquals(listOf(DayOfWeek.MONDAY), created.days)
        assertTrue(created.active)
        assertEquals(now, created.createdTime)
        assertEquals(LocalDate.of(2026, 10, 11), created.cycleStartDay)
        assertEquals("", created.instructions)
        assertEquals(created, db.reminders.getValue(created.id))
    }

    @Test
    fun `should create a continuous interval reminder that starts now unless told otherwise`() = runTest {
        setUpVitamin()

        val now = create(ReminderType.CONTINUOUS_INTERVAL, ReminderChanges(amount = "1", intervalMinutes = 240))
        val later = create(ReminderType.CONTINUOUS_INTERVAL, ReminderChanges(amount = "1", intervalMinutes = 90, intervalStart = Instant.parse("2026-10-11T06:00:00Z")))

        assertEquals(ReminderType.CONTINUOUS_INTERVAL, now.reminderType)
        assertEquals(ReminderTime(240, isDuration = true), now.time)
        assertEquals(this@ReminderWriterTest.now, now.intervalStart)
        assertEquals(Instant.parse("2026-10-11T06:00:00Z"), later.intervalStart)
    }

    @Test
    fun `should create a windowed interval reminder with the app's default window and allow an overnight one`() = runTest {
        setUpVitamin()

        val daytime = create(ReminderType.WINDOWED_INTERVAL, ReminderChanges(amount = "1", intervalMinutes = 120))
        val overnight = create(ReminderType.WINDOWED_INTERVAL, ReminderChanges(amount = "1", intervalMinutes = 120, windowStart = LocalTime.of(22, 0), windowEnd = LocalTime.of(6, 0)))

        assertEquals(ReminderType.WINDOWED_INTERVAL, daytime.reminderType)
        assertEquals(LocalTime.of(8, 0), daytime.intervalStartTimeOfDay)
        assertEquals(LocalTime.of(23, 0), daytime.intervalEndTimeOfDay)
        assertEquals(LocalTime.of(22, 0), overnight.intervalStartTimeOfDay)
        assertEquals(LocalTime.of(6, 0), overnight.intervalEndTimeOfDay)
    }

    @Test
    fun `should refuse a window that starts and ends at the same time`() = runTest {
        setUpVitamin()

        val message = assertFailsWith<ToolArgumentException> {
            create(ReminderType.WINDOWED_INTERVAL, ReminderChanges(amount = "1", intervalMinutes = 60, windowStart = LocalTime.of(8, 0), windowEnd = LocalTime.of(8, 0)))
        }.message

        assertEquals("window_start and window_end must not be the same time", message)
        assertTrue(db.reminders.isEmpty())
    }

    @Test
    fun `should create a linked reminder that follows another reminder of the same medicine`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1))

        val created = create(ReminderType.LINKED, ReminderChanges(amount = "1", delayMinutes = 90), linkedReminderId = 10)

        assertEquals(ReminderType.LINKED, created.reminderType)
        assertEquals(10, created.linkedReminderId)
        assertEquals(ReminderTime(90, isDuration = true), created.time)
    }

    @Test
    fun `should refuse a linked reminder with a parent it cannot follow`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1), reminder(11, 1).copy(windowedInterval = true))
        db.add(medicine(2, "Medicine A"), reminder(20, 2))
        val changes = ReminderChanges(amount = "1", delayMinutes = 30)

        assertTrue(assertFailsWith<ToolArgumentException> { create(ReminderType.LINKED, changes) }.message.orEmpty().contains("linked_reminder_id is required"))
        assertTrue(assertFailsWith<ToolArgumentException> { create(ReminderType.LINKED, changes, linkedReminderId = 99) }.message.orEmpty().contains("not found"))
        assertTrue(assertFailsWith<ToolArgumentException> { create(ReminderType.LINKED, changes, linkedReminderId = 20) }.message.orEmpty().contains("another medicine"))
        assertTrue(assertFailsWith<ToolArgumentException> { create(ReminderType.LINKED, changes, linkedReminderId = 11) }.message.orEmpty().contains("WINDOWED_INTERVAL"))
        assertEquals(setOf(10, 11, 20), db.reminders.keys)
    }

    @Test
    fun `should refuse arguments that do not apply to the type and name them`() = runTest {
        setUpVitamin()

        val message = assertFailsWith<ToolArgumentException> {
            create(ReminderType.TIME_BASED, ReminderChanges(amount = "1", time = LocalTime.of(8, 0), intervalMinutes = 60))
        }.message.orEmpty()

        assertTrue(message, message.startsWith("interval_minutes does not apply to a TIME_BASED reminder"))
        assertTrue(db.reminders.isEmpty())
    }

    @Test
    fun `should require what the app has no sensible default for`() = runTest {
        setUpVitamin()

        val noTime = assertFailsWith<ToolArgumentException> { create(ReminderType.TIME_BASED, ReminderChanges(amount = "1")) }.message
        val nothing = assertFailsWith<ToolArgumentException> { create(ReminderType.CONTINUOUS_INTERVAL, ReminderChanges()) }.message

        assertEquals("time is required for a TIME_BASED reminder", noTime)
        assertEquals("amount, interval_minutes are required for a CONTINUOUS_INTERVAL reminder", nothing)
    }

    @Test
    fun `should refuse a reminder for a medicine that does not exist`() = runTest {
        assertFailsWith<ToolArgumentException> { create(ReminderType.TIME_BASED, ReminderChanges(amount = "1", time = LocalTime.of(8, 0)), medicineId = 99) }
    }

    @Test
    fun `should refuse a period that ends before it starts`() = runTest {
        setUpVitamin()

        val message = assertFailsWith<ToolArgumentException> {
            create(ReminderType.TIME_BASED, ReminderChanges(amount = "1", time = LocalTime.of(8, 0), periodStart = LocalDate.of(2026, 11, 1), periodEnd = LocalDate.of(2026, 10, 1)))
        }.message

        assertEquals("period_start must not be after period_end", message)
        assertTrue(db.reminders.isEmpty())
    }

    @Test
    fun `should not create stock or expiry reminders`() = runTest {
        setUpVitamin()

        assertFailsWith<ToolArgumentException> { create(ReminderType.OUT_OF_STOCK, ReminderChanges(amount = "1")) }
        assertFailsWith<ToolArgumentException> { create(ReminderType.EXPIRATION_DATE, ReminderChanges(amount = "1")) }
        assertTrue(db.reminders.isEmpty())
    }

    @Test
    fun `should change only the given fields of a reminder`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1, amount = "1").copy(instructions = "after breakfast", time = ReminderTime(LocalTime.of(8, 0))))

        val updated = writer.update(10, ReminderChanges(time = LocalTime.of(9, 15))).value

        assertEquals(LocalTime.of(9, 15), updated.time.getLocalTime())
        assertEquals("1", updated.amount)
        assertEquals("after breakfast", updated.instructions)
        assertEquals(updated, db.reminders.getValue(10))
    }

    @Test
    fun `should remove a period limit when it is cleared`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1).copy(periodStart = LocalDate.of(2026, 10, 1), periodEnd = LocalDate.of(2026, 12, 1)))

        val updated = writer.update(10, ReminderChanges(periodEnd = LocalDate.EPOCH)).value

        assertEquals(LocalDate.of(2026, 10, 1), updated.periodStart)
        assertEquals(LocalDate.EPOCH, updated.periodEnd)
    }

    @Test
    fun `should refuse to change a field that does not belong to the type of the reminder`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1))

        val message = assertFailsWith<ToolArgumentException> { writer.update(10, ReminderChanges(intervalMinutes = 60)) }.message.orEmpty()

        assertTrue(message, message.startsWith("interval_minutes does not apply to a TIME_BASED reminder"))
    }

    @Test
    fun `should refuse an update without any change`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1))

        val message = assertFailsWith<ToolArgumentException> { writer.update(10, ReminderChanges()) }.message

        assertEquals("Specify at least one field to change", message)
    }

    @Test
    fun `should refuse to change or delete a reminder that the app manages itself`() = runTest {
        val outOfStock = reminder(10, 1).copy(outOfStockReminderType = Reminder.OutOfStockReminderType.ONCE)
        db.add(medicine(1, "Vitamin X 500 mg"), outOfStock)

        assertFailsWith<ToolArgumentException> { writer.update(10, ReminderChanges(amount = "5")) }
        assertFailsWith<ToolArgumentException> { writer.delete(10) }
        assertEquals(outOfStock, db.reminders.getValue(10))
    }

    @Test
    fun `should refuse to change or delete a reminder that does not exist`() = runTest {
        assertFailsWith<ToolArgumentException> { writer.update(99, ReminderChanges(amount = "1")) }
        assertFailsWith<ToolArgumentException> { writer.delete(99) }
    }

    @Test
    fun `should delete a reminder together with the reminders that follow it`() = runTest {
        db.add(
            medicine(1, "Vitamin X 500 mg"),
            reminder(10, 1),
            reminder(11, 1).copy(linkedReminderId = 10),
            reminder(12, 1).copy(linkedReminderId = 11),
            reminder(13, 1)
        )

        val deleted = writer.delete(10).value

        assertEquals(listOf(10, 11, 12), deleted)
        assertEquals(setOf(13), db.reminders.keys)
    }

    @Test
    fun `should pause the given reminders and leave the others running`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1), reminder(11, 1))

        val affected = writer.setActive(listOf(10), null, active = false).value

        assertEquals(listOf(10), affected)
        assertFalse(db.reminders.getValue(10).active)
        assertTrue(db.reminders.getValue(11).active)
    }

    @Test
    fun `should pause every reminder of a medicine and resume them again`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1), reminder(11, 1))
        db.add(medicine(2, "Medicine A"), reminder(20, 2))

        writer.setActive(null, 1, active = false)
        assertEquals(listOf(false, false, true), listOf(10, 11, 20).map { db.reminders.getValue(it).active })

        writer.setActive(null, 1, active = true)
        assertEquals(listOf(true, true, true), listOf(10, 11, 20).map { db.reminders.getValue(it).active })
    }

    @Test
    fun `should change nothing when one of the reminders to pause does not exist`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1))

        assertFailsWith<ToolArgumentException> { writer.setActive(listOf(10, 99), null, active = false) }

        assertTrue(db.reminders.getValue(10).active)
    }

    @Test
    fun `should refuse to pause the reminders of a medicine that does not exist`() = runTest {
        assertFailsWith<ToolArgumentException> { writer.setActive(null, 99, active = false) }
    }

    @Test
    fun `should create a reminder that follows its medicine unless a priority is given`() = runTest {
        setUpVitamin()

        val following = create(ReminderType.TIME_BASED, ReminderChanges(amount = "1", time = LocalTime.of(8, 0)))
        val high = create(ReminderType.TIME_BASED, ReminderChanges(amount = "1", time = LocalTime.of(9, 0), notificationImportance = Reminder.NotificationImportance.HIGH))

        assertEquals(Reminder.NotificationImportance.SAME_AS_MEDICINE, following.notificationImportance)
        assertEquals(Reminder.NotificationImportance.HIGH, high.notificationImportance)
    }

    @Test
    fun `should change only the priority of a reminder`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1, amount = "2"))

        val updated = writer.update(10, ReminderChanges(notificationImportance = Reminder.NotificationImportance.HIGH_AND_ALARM)).value

        assertEquals(Reminder.NotificationImportance.HIGH_AND_ALARM, updated.notificationImportance)
        assertEquals("2", updated.amount)
    }

    @Test
    fun `should accept a priority for every type of reminder that can be created`() = runTest {
        setUpVitamin()
        db.add(medicine(2, "Medicine A"), reminder(20, 2))
        val high = Reminder.NotificationImportance.HIGH

        create(ReminderType.CONTINUOUS_INTERVAL, ReminderChanges(amount = "1", intervalMinutes = 60, notificationImportance = high))
        create(ReminderType.WINDOWED_INTERVAL, ReminderChanges(amount = "1", intervalMinutes = 60, notificationImportance = high))
        val linked = create(ReminderType.LINKED, ReminderChanges(amount = "1", delayMinutes = 5, notificationImportance = high), linkedReminderId = 20, medicineId = 2)

        assertEquals(high, linked.notificationImportance)
        assertTrue(db.reminders.values.filter { it.id != 20 }.all { it.notificationImportance == high })
    }

    @Test
    fun `should list every argument of a reminder as provided exactly when it is set`() {
        val everything = ReminderChanges(
            amount = "1", instructions = "x", variableAmount = true, automaticallyTaken = true, time = LocalTime.NOON, days = emptyList(), consecutiveDays = 1,
            pauseDays = 1, cycleStartDay = LocalDate.EPOCH, periodStart = LocalDate.EPOCH, periodEnd = LocalDate.EPOCH, activeDaysOfMonth = emptyList(),
            intervalMinutes = 1, intervalStart = Instant.EPOCH, intervalStartsFromProcessed = true, windowStart = LocalTime.NOON, windowEnd = LocalTime.NOON,
            delayMinutes = 1, notificationImportance = Reminder.NotificationImportance.HIGH
        )

        assertEquals(ReminderFields.ALL, everything.providedFields())
        assertEquals(emptySet<String>(), ReminderChanges().providedFields())
    }
}
