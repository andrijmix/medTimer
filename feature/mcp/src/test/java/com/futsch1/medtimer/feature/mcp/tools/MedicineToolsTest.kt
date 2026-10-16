package com.futsch1.medtimer.feature.mcp.tools

import com.futsch1.medtimer.core.domain.model.Medicine
import com.futsch1.medtimer.core.domain.model.MedicineToTag
import com.futsch1.medtimer.core.domain.model.Reminder
import com.futsch1.medtimer.core.domain.model.ReminderTime
import com.futsch1.medtimer.core.domain.model.Tag
import com.futsch1.medtimer.core.domain.repository.MedicineRepository
import com.futsch1.medtimer.core.domain.repository.TagRepository
import com.futsch1.medtimer.feature.mcp.call
import com.futsch1.medtimer.feature.mcp.fixedTime
import com.futsch1.medtimer.feature.mcp.json
import com.futsch1.medtimer.feature.mcp.medicine
import com.futsch1.medtimer.feature.mcp.named
import com.futsch1.medtimer.feature.mcp.reminder
import com.futsch1.medtimer.feature.mcp.text
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

class MedicineToolsTest {

    private fun tools(
        medicines: List<com.futsch1.medtimer.core.domain.model.Medicine> = emptyList(),
        tags: List<Tag> = emptyList(),
        links: List<MedicineToTag> = emptyList()
    ): List<ToolDefinition> {
        val medicineRepository = mock<MedicineRepository> {
            onBlocking { getAll() } doReturn medicines
            onBlocking { fetch(any()) } doReturn null
            medicines.forEach { medicine -> onBlocking { fetch(medicine.id) } doReturn medicine }
        }
        val tagRepository = mock<TagRepository> {
            on { getAllFlow() } doReturn flowOf(tags)
            on { getMedicineTagsFlow() } doReturn flowOf(links)
        }
        return MedicineTools(medicineRepository, tagRepository, fixedTime()).definitions()
    }

    @Test
    fun `should list medicines by name with stock only when stock is managed`() = runTest {
        val tools = tools(listOf(medicine(1, "Vitamin X 500 mg"), medicine(2, "Medicine A", amount = 12.5)))

        val medicines = tools.named("list_medicines").call().json().getValue("medicines").jsonArray.map { it.jsonObject }

        assertEquals(listOf("Medicine A", "Vitamin X 500 mg"), medicines.map { it.string("name") })
        assertEquals(12.5, medicines[0].getValue("stock").jsonPrimitive.doubleOrNull)
        assertEquals(JsonNull, medicines[1].getValue("stock"))
    }

    @Test
    fun `should show the notification priority of medicines and reminders`() = runTest {
        val high = medicine(1, "Medicine A", listOf(reminder(10, 1).copy(notificationImportance = Reminder.NotificationImportance.HIGH_AND_ALARM)))
            .copy(notificationImportance = Medicine.NotificationImportance.HIGH)
        val alarm = medicine(2, "Vitamin X 500 mg").copy(notificationImportance = Medicine.NotificationImportance.HIGH, showNotificationAsAlarm = true)
        val tools = tools(listOf(high, alarm, medicine(3, "Zinc")))

        val listed = tools.named("list_medicines").call().json().getValue("medicines").jsonArray.map { it.jsonObject.string("notification_priority") }
        val reminder = tools.named("get_medicine").call("""{"medicine_id":1}""").json().getValue("reminders").jsonArray.single().jsonObject

        assertEquals(listOf("HIGH", "HIGH_AND_ALARM", "DEFAULT"), listed)
        assertEquals("HIGH_AND_ALARM", reminder.string("notification_priority"))
    }

    @Test
    fun `should count only the reminders for taking a dose`() = runTest {
        val reminders = listOf(
            reminder(10, 1),
            reminder(11, 1).copy(active = false),
            reminder(12, 1).copy(outOfStockReminderType = Reminder.OutOfStockReminderType.ONCE)
        )
        val tools = tools(listOf(medicine(1, "Vitamin X 500 mg", reminders)))

        val summary = tools.named("list_medicines").call().json().getValue("medicines").jsonArray.single().jsonObject

        assertEquals(2, summary.getValue("reminder_count").jsonPrimitive.intOrNull)
        assertEquals(1, summary.getValue("active_reminder_count").jsonPrimitive.intOrNull)
    }

    @Test
    fun `should describe a time based reminder with every day spelled out`() = runTest {
        val reminder = reminder(10, 1, amount = "2").copy(time = ReminderTime(LocalTime.of(8, 30)), instructions = "after breakfast")
        val tools = tools(listOf(medicine(1, "Vitamin X 500 mg", listOf(reminder))))

        val json = tools.named("get_medicine").call("""{"medicine_id":1}""").json().getValue("reminders").jsonArray.single().jsonObject

        assertEquals("TIME_BASED", json.string("type"))
        assertEquals("08:30", json.string("time"))
        assertEquals("2", json.string("amount"))
        assertEquals("after breakfast", json.string("instructions"))
        assertEquals(DayOfWeek.entries.map { it.name }, json.getValue("days").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonNull, json.getValue("cycle"))
        assertTrue(json.getValue("editable_via_mcp").jsonPrimitive.booleanOrNull!!)
    }

    @Test
    fun `should list the chosen days in the order of the week and describe a cycle`() = runTest {
        val reminder = reminder(10, 1).copy(days = listOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY), consecutiveDays = 21, pauseDays = 7)
        val tools = tools(listOf(medicine(1, "Vitamin X 500 mg", listOf(reminder))))

        val json = tools.named("get_medicine").call("""{"medicine_id":1}""").json().getValue("reminders").jsonArray.single().jsonObject

        assertEquals(listOf("MONDAY", "WEDNESDAY"), json.getValue("days").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(21, json.getValue("cycle").jsonObject.getValue("consecutive_days").jsonPrimitive.intOrNull)
        assertEquals(7, json.getValue("cycle").jsonObject.getValue("pause_days").jsonPrimitive.intOrNull)
    }

    @Test
    fun `should describe linked and interval reminders by their own fields`() = runTest {
        val linked = reminder(11, 1).copy(linkedReminderId = 10, time = ReminderTime(90, isDuration = true))
        val continuous = reminder(12, 1).copy(intervalStart = Instant.parse("2026-10-10T06:00:00Z"), time = ReminderTime(240, isDuration = true))
        val windowed = reminder(13, 1).copy(
            windowedInterval = true,
            time = ReminderTime(120, isDuration = true),
            intervalStartTimeOfDay = LocalTime.of(8, 0),
            intervalEndTimeOfDay = LocalTime.of(20, 0)
        )
        val tools = tools(listOf(medicine(1, "Vitamin X 500 mg", listOf(linked, continuous, windowed))))

        val reminders = tools.named("get_medicine").call("""{"medicine_id":1}""").json().getValue("reminders").jsonArray.map { it.jsonObject }

        assertEquals(listOf("LINKED", "CONTINUOUS_INTERVAL", "WINDOWED_INTERVAL"), reminders.map { it.string("type") })
        assertEquals(10, reminders[0].getValue("linked_reminder_id").jsonPrimitive.intOrNull)
        assertEquals(90, reminders[0].getValue("delay_minutes").jsonPrimitive.intOrNull)
        assertEquals(240, reminders[1].getValue("interval_minutes").jsonPrimitive.intOrNull)
        assertEquals("2026-10-10T08:00:00+02:00", reminders[1].string("interval_start"))
        assertEquals("08:00", reminders[2].string("window_start"))
        assertEquals("20:00", reminders[2].string("window_end"))
    }

    @Test
    fun `should mark reminders made by the app as not editable`() = runTest {
        val outOfStock = reminder(10, 1).copy(outOfStockReminderType = Reminder.OutOfStockReminderType.ONCE, outOfStockThreshold = 3.0)
        val tools = tools(listOf(medicine(1, "Vitamin X 500 mg", listOf(outOfStock))))

        val json = tools.named("get_medicine").call("""{"medicine_id":1}""").json().getValue("reminders").jsonArray.single().jsonObject

        assertEquals("OUT_OF_STOCK", json.string("type"))
        assertFalse(json.getValue("editable_via_mcp").jsonPrimitive.booleanOrNull!!)
        assertEquals(3.0, json.getValue("out_of_stock_threshold").jsonPrimitive.doubleOrNull)
    }

    @Test
    fun `should report an unknown medicine as an error that names the id`() = runTest {
        val result = tools().named("get_medicine").call("""{"medicine_id":99}""")

        assertEquals(true, result.isError)
        assertTrue(result.text(), result.text().contains("medicine_id 99 not found"))
    }

    @Test
    fun `should report a missing argument as an error`() = runTest {
        val result = tools().named("get_medicine").call()

        assertEquals(true, result.isError)
        assertEquals("medicine_id is required", result.text())
    }

    @Test
    fun `should list tags with the medicines that carry them`() = runTest {
        val tools = tools(
            tags = listOf(Tag("Morning", 1), Tag("evening", 2)),
            links = listOf(MedicineToTag(medicineId = 1, tagId = 1), MedicineToTag(medicineId = 2, tagId = 1))
        )

        val tags = tools.named("list_tags").call().json().getValue("tags").jsonArray.map { it.jsonObject }

        assertEquals(listOf("evening", "Morning"), tags.map { it.string("name") })
        assertEquals(emptyList<Int>(), tags[0].getValue("medicine_ids").jsonArray.map { it.jsonPrimitive.intOrNull })
        assertEquals(listOf(1, 2), tags[1].getValue("medicine_ids").jsonArray.map { it.jsonPrimitive.intOrNull })
    }

    @Test
    fun `should mark every read tool as read only`() {
        val definitions = tools()

        assertTrue(definitions.all { it.annotations.readOnlyHint == true })
        assertNull(definitions.firstOrNull { it.annotations.destructiveHint == true })
    }
}
