package com.futsch1.medtimer.feature.mcp.domain

import com.futsch1.medtimer.core.domain.model.Medicine
import com.futsch1.medtimer.core.domain.model.Reminder
import com.futsch1.medtimer.feature.mcp.FakeDatabase
import com.futsch1.medtimer.feature.mcp.medicine
import com.futsch1.medtimer.feature.mcp.reminder
import com.futsch1.medtimer.feature.mcp.tools.ToolArgumentException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertFailsWith

class MedicineWriterTest {

    private val db = FakeDatabase()
    private val writer = MedicineWriter(db.medicineRepository, db.reminderRepository, db.tagRepository)

    private fun tagNamesOf(medicineId: Int) = db.medicines.getValue(medicineId).let { medicine ->
        db.links.filter { it.first == medicine.id }.map { db.tags.getValue(it.second).name }.sorted()
    }

    @Test
    fun `should create a medicine from the given fields with the sort order the app uses`() = runTest {
        db.add(medicine(5, "Existing").copy(sortOrder = 3.0))

        val created = writer.create(
            MedicineChanges(
                name = "Vitamin X 500 mg", unit = "mg", stock = 30.0, refillSize = 60.0, notes = "with food", cannotBeSkipped = true,
                expirationDate = LocalDate.of(2027, 1, 31)
            )
        ).value

        assertEquals("Vitamin X 500 mg", created.name)
        assertEquals("mg", created.unit)
        assertEquals(30.0, created.amount, 0.0)
        assertEquals(60.0, created.refillSize, 0.0)
        assertEquals("with food", created.notes)
        assertTrue(created.cannotBeSkipped)
        assertEquals(LocalDate.of(2027, 1, 31), created.expirationDate)
        assertEquals(LocalDate.EPOCH, created.productionDate)
        // The app's "add medicine" dialog uses the highest sort order as it is, not plus one.
        assertEquals(3.0, created.sortOrder, 0.0)
        assertTrue(created.id > 0)
    }

    @Test
    fun `should create the tags of a new medicine and reuse tags that exist`() = runTest {
        db.tagRepository.create(com.futsch1.medtimer.core.domain.model.Tag("Morning", 0))

        val created = writer.create(MedicineChanges(name = "Vitamin X 500 mg", tags = listOf("Morning", "Heart"))).value

        assertEquals(listOf("Heart", "Morning"), created.tags.map { it.name }.sorted())
        assertEquals(2, db.tags.size)
    }

    @Test
    fun `should warn about a medicine with the same name but still create it`() = runTest {
        db.add(medicine(5, "vitamin x 500 mg"))

        val result = writer.create(MedicineChanges(name = "Vitamin X 500 mg"))

        assertEquals(2, db.medicines.size)
        assertTrue(result.warnings.single(), result.warnings.single().contains("id 5"))
    }

    @Test
    fun `should change only the given fields of a medicine`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg", amount = 10.0).copy(unit = "mg", notes = "old"))

        val updated = writer.update(1, MedicineChanges(notes = "new", stock = 12.5)).value

        assertEquals("Vitamin X 500 mg", updated.name)
        assertEquals("mg", updated.unit)
        assertEquals("new", updated.notes)
        assertEquals(12.5, updated.amount, 0.0)
    }

    @Test
    fun `should remove a date when it is cleared`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg").copy(expirationDate = LocalDate.of(2027, 1, 31)))

        val updated = writer.update(1, MedicineChanges(expirationDate = LocalDate.EPOCH)).value

        assertEquals(LocalDate.EPOCH, updated.expirationDate)
    }

    @Test
    fun `should replace the tags of a medicine and leave its reminders alone`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1))
        writer.update(1, MedicineChanges(tags = listOf("Morning", "Heart")))

        val updated = writer.update(1, MedicineChanges(tags = listOf("heart", "Evening"))).value

        assertEquals(listOf("Evening", "Heart"), tagNamesOf(1))
        assertEquals(listOf(10), updated.reminders.map { it.id })
    }

    @Test
    fun `should remove all tags when given an empty list`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"))
        writer.update(1, MedicineChanges(tags = listOf("Morning")))

        writer.update(1, MedicineChanges(tags = emptyList()))

        assertEquals(emptyList<String>(), tagNamesOf(1))
    }

    @Test
    fun `should create a medicine with the priority the app's settings screen offers`() = runTest {
        val default = writer.create(MedicineChanges(name = "A")).value
        val high = writer.create(MedicineChanges(name = "B", notificationPriority = MedicinePriority.HIGH)).value
        val alarm = writer.create(MedicineChanges(name = "C", notificationPriority = MedicinePriority.HIGH_AND_ALARM)).value

        assertEquals(Medicine.NotificationImportance.DEFAULT, default.notificationImportance)
        assertFalse(default.showNotificationAsAlarm)
        assertEquals(Medicine.NotificationImportance.HIGH, high.notificationImportance)
        assertFalse(high.showNotificationAsAlarm)
        assertEquals(Medicine.NotificationImportance.HIGH, alarm.notificationImportance)
        assertTrue(alarm.showNotificationAsAlarm)
    }

    @Test
    fun `should switch the alarm off again when the priority goes back down`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg").copy(notificationImportance = Medicine.NotificationImportance.HIGH, showNotificationAsAlarm = true))

        val high = writer.update(1, MedicineChanges(notificationPriority = MedicinePriority.HIGH)).value
        val default = writer.update(1, MedicineChanges(notificationPriority = MedicinePriority.DEFAULT)).value

        assertEquals(MedicinePriority.HIGH, MedicinePriority.of(high))
        assertEquals(MedicinePriority.DEFAULT, MedicinePriority.of(default))
        assertFalse(default.showNotificationAsAlarm)
    }

    @Test
    fun `should leave the priority alone when it is not part of the change`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg").copy(notificationImportance = Medicine.NotificationImportance.HIGH, showNotificationAsAlarm = true))

        val updated = writer.update(1, MedicineChanges(notes = "x")).value

        assertEquals(MedicinePriority.HIGH_AND_ALARM, MedicinePriority.of(updated))
    }

    @Test
    fun `should refuse to change a medicine that does not exist`() = runTest {
        assertFailsWith<ToolArgumentException> { writer.update(99, MedicineChanges(notes = "x")) }
    }

    @Test
    fun `should delete a medicine with its reminders and tag links and nothing else`() = runTest {
        val linked: Reminder = reminder(11, 1).copy(linkedReminderId = 10)
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1), linked)
        db.add(medicine(2, "Medicine A"), reminder(20, 2))
        writer.update(1, MedicineChanges(tags = listOf("Morning")))
        writer.update(2, MedicineChanges(tags = listOf("Morning")))

        val deleted = writer.delete(1, "Vitamin X 500 mg").value

        assertEquals(listOf(10, 11), deleted.deletedReminderIds)
        assertFalse(1 in db.medicines)
        assertEquals(setOf(20), db.reminders.keys)
        assertEquals(1, db.links.size)
        assertEquals(2, db.links.single().first)
        assertEquals(1, db.tags.size)
    }

    @Test
    fun `should accept the confirmation name regardless of case and surrounding spaces`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"))

        writer.delete(1, "  vitamin x 500 MG ")

        assertTrue(db.medicines.isEmpty())
    }

    @Test
    fun `should delete nothing when the confirmation name is wrong`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1))

        assertFailsWith<ToolArgumentException> { writer.delete(1, "Medicine A") }

        assertTrue(1 in db.medicines)
        assertEquals(setOf(10), db.reminders.keys)
    }

    @Test
    fun `should refuse to delete a medicine that does not exist`() = runTest {
        assertFailsWith<ToolArgumentException> { writer.delete(99, "anything") }
    }
}
