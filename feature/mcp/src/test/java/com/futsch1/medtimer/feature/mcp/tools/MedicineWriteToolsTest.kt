package com.futsch1.medtimer.feature.mcp.tools

import com.futsch1.medtimer.feature.mcp.FakeDatabase
import com.futsch1.medtimer.feature.mcp.call
import com.futsch1.medtimer.feature.mcp.domain.MedicineWriter
import com.futsch1.medtimer.feature.mcp.domain.WriteCoordinator
import com.futsch1.medtimer.feature.mcp.fixedTime
import com.futsch1.medtimer.feature.mcp.json
import com.futsch1.medtimer.feature.mcp.medicine
import com.futsch1.medtimer.feature.mcp.named
import com.futsch1.medtimer.feature.mcp.reminder
import com.futsch1.medtimer.feature.mcp.text
import com.futsch1.medtimer.feature.reminders.api.command.ReminderCommandBus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import java.time.LocalDate

class MedicineWriteToolsTest {

    private val db = FakeDatabase()
    private val bus = mock<ReminderCommandBus>()
    private val tools = MedicineWriteTools(
        MedicineWriter(db.medicineRepository, db.reminderRepository, db.tagRepository),
        WriteCoordinator(bus),
        fixedTime()
    ).definitions()

    @Test
    fun `should create a medicine and reschedule the reminders`() = runTest {
        val result = tools.named("create_medicine").call(
            """{"name":"Vitamin X 500 mg","unit":"mg","stock":30,"tags":["Morning"],"expiration_date":"2027-01-31","cannot_be_skipped":true}"""
        )

        val json = result.json()
        assertTrue(json.getValue("id").jsonPrimitive.intOrNull!! > 0)
        assertEquals("Vitamin X 500 mg", json.getValue("name").jsonPrimitive.content)
        assertEquals(30.0, json.getValue("stock").jsonPrimitive.doubleOrNull)
        assertEquals("2027-01-31", json.getValue("expiration_date").jsonPrimitive.content)
        assertEquals(listOf("Morning"), json.getValue("tags").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, db.medicines.size)
        verifyBlocking(bus) { scheduleNextNotification() }
    }

    @Test
    fun `should pass a warning about a duplicate name on to the user`() = runTest {
        db.add(medicine(5, "Vitamin X 500 mg"))

        val json = tools.named("create_medicine").call("""{"name":"Vitamin X 500 mg"}""").json()

        assertEquals(1, json.getValue("warnings").jsonArray.size)
        assertEquals(2, db.medicines.size)
    }

    @Test
    fun `should refuse a bad creation without creating anything or rescheduling`() = runTest {
        val noName = tools.named("create_medicine").call("{}")
        val blankName = tools.named("create_medicine").call("""{"name":"   "}""")
        val unknownArgument = tools.named("create_medicine").call("""{"name":"Vitamin X 500 mg","dose":1}""")
        val negativeStock = tools.named("create_medicine").call("""{"name":"Vitamin X 500 mg","stock":-1}""")
        val tooManyTags = tools.named("create_medicine").call("""{"name":"Vitamin X 500 mg","tags":[${(1..21).joinToString { "\"t$it\"" }}]}""")

        assertEquals("name is required", noName.text())
        assertEquals("name must not be empty", blankName.text())
        assertTrue(unknownArgument.text(), unknownArgument.text().contains("'dose'"))
        assertEquals("stock must be at least 0.0, got -1.0", negativeStock.text())
        assertEquals("tags may have at most 20 entries", tooManyTags.text())
        assertTrue(listOf(noName, blankName, unknownArgument, negativeStock, tooManyTags).all { it.isError == true })
        assertTrue(db.medicines.isEmpty())
        verifyBlocking(bus, never()) { scheduleNextNotification() }
    }

    @Test
    fun `should change a medicine and remove a date that is set to null`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg").copy(expirationDate = LocalDate.of(2027, 1, 31), notes = "old"))

        val json = tools.named("update_medicine").call("""{"medicine_id":1,"notes":"new","expiration_date":null}""").json()

        assertEquals("new", json.getValue("notes").jsonPrimitive.content)
        assertEquals(JsonNull, json.getValue("expiration_date"))
        assertEquals("new", db.medicines.getValue(1).notes)
        verifyBlocking(bus) { scheduleNextNotification() }
    }

    @Test
    fun `should refuse an update that changes nothing or targets an unknown medicine`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"))

        val nothing = tools.named("update_medicine").call("""{"medicine_id":1}""")
        val unknown = tools.named("update_medicine").call("""{"medicine_id":99,"notes":"x"}""")

        assertEquals("Specify at least one field to change", nothing.text())
        assertTrue(unknown.text(), unknown.text().contains("medicine_id 99 not found"))
        verifyBlocking(bus, never()) { scheduleNextNotification() }
    }

    @Test
    fun `should delete a medicine with its reminders when the name is confirmed`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1), reminder(11, 1))

        val json = tools.named("delete_medicine").call("""{"medicine_id":1,"confirm_name":"Vitamin X 500 mg"}""").json()

        assertEquals(1, json.getValue("deleted_medicine_id").jsonPrimitive.intOrNull)
        assertEquals(listOf(10, 11), json.getValue("deleted_reminder_ids").jsonArray.map { it.jsonPrimitive.intOrNull })
        assertTrue(db.medicines.isEmpty())
        assertTrue(db.reminders.isEmpty())
        verifyBlocking(bus) { scheduleNextNotification() }
    }

    @Test
    fun `should keep the medicine when the confirmation name is wrong`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1))

        val result = tools.named("delete_medicine").call("""{"medicine_id":1,"confirm_name":"Medicine A"}""")

        assertEquals(true, result.isError)
        assertTrue(result.text(), result.text().startsWith("confirm_name does not match"))
        assertEquals(1, db.medicines.size)
        assertEquals(1, db.reminders.size)
        verifyBlocking(bus, never()) { scheduleNextNotification() }
    }

    @Test
    fun `should require the confirmation name`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"))

        assertEquals("confirm_name is required", tools.named("delete_medicine").call("""{"medicine_id":1}""").text())
        assertEquals(1, db.medicines.size)
    }

    @Test
    fun `should set the notification priority of a medicine and report it back`() = runTest {
        val created = tools.named("create_medicine").call("""{"name":"Vitamin X 500 mg","notification_priority":"high"}""").json()
        val id = created.getValue("id").jsonPrimitive.intOrNull!!

        val alarm = tools.named("update_medicine").call("""{"medicine_id":$id,"notification_priority":"HIGH_AND_ALARM"}""").json()

        assertEquals("HIGH", created.getValue("notification_priority").jsonPrimitive.content)
        assertEquals("HIGH_AND_ALARM", alarm.getValue("notification_priority").jsonPrimitive.content)
        assertTrue(db.medicines.getValue(id).showNotificationAsAlarm)
        verifyBlocking(bus, times(2)) { scheduleNextNotification() }
    }

    @Test
    fun `should refuse a notification priority that does not exist`() = runTest {
        val result = tools.named("create_medicine").call("""{"name":"Vitamin X 500 mg","notification_priority":"loud"}""")

        assertEquals(true, result.isError)
        assertEquals("notification_priority must be one of DEFAULT, HIGH, HIGH_AND_ALARM, got \"loud\"", result.text())
        assertTrue(db.medicines.isEmpty())
    }

    @Test
    fun `should mark the tools by what they can do to the data`() {
        assertEquals(false, tools.named("create_medicine").annotations.destructiveHint)
        assertEquals(true, tools.named("update_medicine").annotations.destructiveHint)
        assertEquals(true, tools.named("delete_medicine").annotations.destructiveHint)
        assertTrue(tools.all { it.annotations.readOnlyHint == false })
        assertNull(tools.firstOrNull { it.annotations.openWorldHint != false })
    }
}
