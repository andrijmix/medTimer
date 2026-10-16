package com.futsch1.medtimer.feature.mcp.tools

import com.futsch1.medtimer.core.domain.model.ReminderType
import com.futsch1.medtimer.feature.mcp.FakeDatabase
import com.futsch1.medtimer.feature.mcp.call
import com.futsch1.medtimer.feature.mcp.domain.ReminderFields
import com.futsch1.medtimer.feature.mcp.domain.ReminderWriter
import com.futsch1.medtimer.feature.mcp.domain.WriteCoordinator
import com.futsch1.medtimer.feature.mcp.fixedTime
import com.futsch1.medtimer.feature.mcp.json
import com.futsch1.medtimer.feature.mcp.medicine
import com.futsch1.medtimer.feature.mcp.named
import com.futsch1.medtimer.feature.mcp.reminder
import com.futsch1.medtimer.feature.mcp.text
import com.futsch1.medtimer.feature.reminders.api.command.ReminderCommandBus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import java.time.DayOfWeek
import java.time.Instant

class ReminderWriteToolsTest {

    private val db = FakeDatabase()
    private val bus = mock<ReminderCommandBus>()
    private val timeAccess = fixedTime()
    private val tools = ReminderWriteTools(ReminderWriter(db.reminderRepository, db.medicineRepository, timeAccess), WriteCoordinator(bus), timeAccess).definitions()

    private fun setUpVitamin() = db.add(medicine(1, "Vitamin X 500 mg"))

    @Test
    fun `should create a time based reminder and reschedule`() = runTest {
        setUpVitamin()

        val json = tools.named("create_reminder").call(
            """{"medicine_id":1,"type":"TIME_BASED","amount":"1","time":"08:30","days":["monday","FRIDAY"],"instructions":"after breakfast"}"""
        ).json()

        assertEquals("TIME_BASED", json.getValue("type").jsonPrimitive.content)
        assertEquals("08:30", json.getValue("time").jsonPrimitive.content)
        assertEquals(listOf("MONDAY", "FRIDAY"), json.getValue("days").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("after breakfast", json.getValue("instructions").jsonPrimitive.content)
        assertEquals(1, db.reminders.size)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), db.reminders.values.single().days)
        verifyBlocking(bus) { scheduleNextNotification() }
    }

    @Test
    fun `should read an interval start without offset in the phone's time zone and one with offset exactly`() = runTest {
        setUpVitamin()

        tools.named("create_reminder").call("""{"medicine_id":1,"type":"CONTINUOUS_INTERVAL","amount":"1","interval_minutes":240,"interval_start":"2026-10-11T08:00"}""").json()
        tools.named("create_reminder").call("""{"medicine_id":1,"type":"CONTINUOUS_INTERVAL","amount":"1","interval_minutes":240,"interval_start":"2026-10-11T08:00:00+03:00"}""").json()

        // Berlin is UTC+2 on that day.
        assertEquals(
            listOf(Instant.parse("2026-10-11T06:00:00Z"), Instant.parse("2026-10-11T05:00:00Z")),
            db.reminders.values.sortedBy { it.id }.map { it.intervalStart }
        )
    }

    @Test
    fun `should create windowed and linked reminders`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1))

        val windowed = tools.named("create_reminder").call(
            """{"medicine_id":1,"type":"WINDOWED_INTERVAL","amount":"1","interval_minutes":120,"window_start":"09:00","window_end":"21:00"}"""
        ).json()
        val linked = tools.named("create_reminder").call(
            """{"medicine_id":1,"type":"LINKED","amount":"1","linked_reminder_id":10,"delay_minutes":30}"""
        ).json()

        assertEquals("09:00", windowed.getValue("window_start").jsonPrimitive.content)
        assertEquals("21:00", windowed.getValue("window_end").jsonPrimitive.content)
        assertEquals(10, linked.getValue("linked_reminder_id").jsonPrimitive.intOrNull)
        assertEquals(30, linked.getValue("delay_minutes").jsonPrimitive.intOrNull)
    }

    @Test
    fun `should refuse a bad creation without creating anything or rescheduling`() = runTest {
        setUpVitamin()
        val create = tools.named("create_reminder")

        val results = mapOf(
            "medicine_id is required" to create.call("""{"type":"TIME_BASED"}"""),
            "type must be one of TIME_BASED, CONTINUOUS_INTERVAL, WINDOWED_INTERVAL, LINKED, got \"DAILY\"" to create.call("""{"medicine_id":1,"type":"DAILY"}"""),
            "linked_reminder_id only applies to a LINKED reminder" to create.call("""{"medicine_id":1,"type":"TIME_BASED","linked_reminder_id":3}"""),
            "time is required for a TIME_BASED reminder" to create.call("""{"medicine_id":1,"type":"TIME_BASED","amount":"1"}"""),
            "time must be a time in the form HH:mm (00:00 to 23:59), got \"25:00\"" to create.call("""{"medicine_id":1,"type":"TIME_BASED","amount":"1","time":"25:00"}"""),
            "interval_minutes must be between 1 and 525600, got 0" to create.call("""{"medicine_id":1,"type":"CONTINUOUS_INTERVAL","amount":"1","interval_minutes":0}"""),
            "days must be one of MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY, got \"Funday\"" to
                create.call("""{"medicine_id":1,"type":"TIME_BASED","amount":"1","time":"08:00","days":["Funday"]}""")
        )

        results.forEach { (expected, result) ->
            assertEquals(true, result.isError)
            assertEquals(expected, result.text())
        }
        val misplaced = create.call("""{"medicine_id":1,"type":"TIME_BASED","amount":"1","time":"08:00","delay_minutes":5}""")
        assertTrue(misplaced.text(), misplaced.text().startsWith("delay_minutes does not apply to a TIME_BASED reminder"))
        assertTrue(db.reminders.isEmpty())
        verifyBlocking(bus, never()) { scheduleNextNotification() }
    }

    @Test
    fun `should change a reminder and leave the rest`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1, amount = "1"))

        val json = tools.named("update_reminder").call("""{"reminder_id":10,"time":"09:15","days":[]}""").json()

        assertEquals("09:15", json.getValue("time").jsonPrimitive.content)
        assertEquals(DayOfWeek.entries.size, json.getValue("days").jsonArray.size)
        assertEquals("1", db.reminders.getValue(10).amount)
        verifyBlocking(bus) { scheduleNextNotification() }
    }

    @Test
    fun `should not accept a change of the type or arguments that are not its own`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1))

        val type = tools.named("update_reminder").call("""{"reminder_id":10,"type":"LINKED"}""")
        val misplaced = tools.named("update_reminder").call("""{"reminder_id":10,"interval_minutes":60}""")

        assertTrue(type.text(), type.text().contains("Unknown argument 'type'"))
        assertTrue(misplaced.text(), misplaced.text().startsWith("interval_minutes does not apply to a TIME_BASED reminder"))
        verifyBlocking(bus, never()) { scheduleNextNotification() }
    }

    @Test
    fun `should pause the reminders of a medicine or the chosen ones`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1), reminder(11, 1))

        val byIds = tools.named("set_reminder_active").call("""{"active":false,"reminder_ids":[10]}""").json()
        val byMedicine = tools.named("set_reminder_active").call("""{"active":false,"medicine_id":1}""").json()

        assertEquals(false, byIds.getValue("active").jsonPrimitive.booleanOrNull)
        assertEquals(listOf(10), byIds.getValue("reminder_ids").jsonArray.map { it.jsonPrimitive.intOrNull })
        assertEquals(listOf(10, 11), byMedicine.getValue("reminder_ids").jsonArray.map { it.jsonPrimitive.intOrNull })
        assertTrue(db.reminders.values.none { it.active })
    }

    @Test
    fun `should require exactly one way of choosing the reminders to pause`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1))
        val setActive = tools.named("set_reminder_active")

        assertEquals("Give either reminder_ids or medicine_id", setActive.call("""{"active":false}""").text())
        assertEquals("Give either reminder_ids or medicine_id", setActive.call("""{"active":false,"reminder_ids":[10],"medicine_id":1}""").text())
        assertEquals("reminder_ids must not be empty", setActive.call("""{"active":false,"reminder_ids":[]}""").text())
        assertEquals("active is required", setActive.call("""{"medicine_id":1}""").text())
        assertTrue(db.reminders.getValue(10).active)
        verifyBlocking(bus, never()) { scheduleNextNotification() }
    }

    @Test
    fun `should delete a reminder with the ones that follow it`() = runTest {
        db.add(medicine(1, "Vitamin X 500 mg"), reminder(10, 1), reminder(11, 1).copy(linkedReminderId = 10), reminder(12, 1))

        val json = tools.named("delete_reminder").call("""{"reminder_id":10}""").json()

        assertEquals(listOf(10, 11), json.getValue("deleted_reminder_ids").jsonArray.map { it.jsonPrimitive.intOrNull })
        assertEquals(setOf(12), db.reminders.keys)
        verifyBlocking(bus) { scheduleNextNotification() }
    }

    @Test
    fun `should set and report the notification priority of a reminder`() = runTest {
        setUpVitamin()

        val following = tools.named("create_reminder").call("""{"medicine_id":1,"type":"TIME_BASED","amount":"1","time":"08:00"}""").json()
        val high = tools.named("create_reminder").call("""{"medicine_id":1,"type":"TIME_BASED","amount":"1","time":"09:00","notification_priority":"high"}""").json()
        val alarm = tools.named("update_reminder").call("""{"reminder_id":2,"notification_priority":"high_and_alarm"}""").json()

        assertEquals("SAME_AS_MEDICINE", following.getValue("notification_priority").jsonPrimitive.content)
        assertEquals("HIGH", high.getValue("notification_priority").jsonPrimitive.content)
        assertEquals("HIGH_AND_ALARM", alarm.getValue("notification_priority").jsonPrimitive.content)
    }

    @Test
    fun `should refuse a reminder priority that does not exist and name the choices`() = runTest {
        setUpVitamin()

        val result = tools.named("create_reminder").call("""{"medicine_id":1,"type":"TIME_BASED","amount":"1","time":"08:00","notification_priority":"loud"}""")

        assertEquals(true, result.isError)
        assertEquals("notification_priority must be one of SAME_AS_MEDICINE, DEFAULT, HIGH, HIGH_AND_ALARM, got \"loud\"", result.text())
        assertTrue(db.reminders.isEmpty())
    }

    @Test
    fun `should offer every reminder argument to both tools that take them`() {
        listOf("create_reminder", "update_reminder").forEach { name ->
            val properties = tools.named(name).inputSchema.properties!!.keys

            assertTrue("$name is missing ${ReminderFields.ALL - properties}", properties.containsAll(ReminderFields.ALL))
        }
        assertEquals(ReminderFields.EDITABLE_TYPES.toSet(), setOf(ReminderType.TIME_BASED, ReminderType.CONTINUOUS_INTERVAL, ReminderType.WINDOWED_INTERVAL, ReminderType.LINKED))
    }

    @Test
    fun `should mark the tools by what they can do to the data`() {
        assertEquals(false, tools.named("create_reminder").annotations.destructiveHint)
        listOf("update_reminder", "set_reminder_active", "delete_reminder").forEach { assertEquals(it, true, tools.named(it).annotations.destructiveHint) }
        assertTrue(tools.all { it.annotations.readOnlyHint == false })
    }
}
