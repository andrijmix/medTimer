package com.futsch1.medtimer.feature.mcp.tools

import com.futsch1.medtimer.core.domain.model.ReminderEvent
import com.futsch1.medtimer.core.domain.model.ReminderEvent.ReminderStatus
import com.futsch1.medtimer.core.domain.repository.MedicineRepository
import com.futsch1.medtimer.core.domain.repository.ReminderEventRepository
import com.futsch1.medtimer.feature.mcp.call
import com.futsch1.medtimer.feature.mcp.event
import com.futsch1.medtimer.feature.mcp.fixedTime
import com.futsch1.medtimer.feature.mcp.json
import com.futsch1.medtimer.feature.mcp.medicine
import com.futsch1.medtimer.feature.mcp.named
import com.futsch1.medtimer.feature.mcp.reminder
import com.futsch1.medtimer.feature.mcp.text
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.time.Instant

class HistoryToolsTest {

    private val medicines = listOf(
        medicine(1, "Vitamin X 500 mg", listOf(reminder(10, 1))),
        medicine(2, "Medicine A", listOf(reminder(20, 2)))
    )

    // Berlin is UTC+2 on 2026-10-10, so that local day starts at 2026-10-09T22:00:00Z.
    private val dayStart = Instant.parse("2026-10-09T22:00:00Z")

    private fun tools(events: List<ReminderEvent>): List<ToolDefinition> {
        val medicineRepository = mock<MedicineRepository> { onBlocking { getAll() } doReturn medicines }
        val eventRepository = mock<ReminderEventRepository> {
            // Like the real query: only events strictly after the given second, newest first.
            on { getAllFlow(any(), any()) } doAnswer { invocation ->
                val after = invocation.getArgument<Instant>(0)
                val statuses = invocation.getArgument<List<ReminderStatus>>(1)
                flowOf(events.filter { it.remindedTimestamp.epochSecond > after.epochSecond && it.status in statuses }.sortedByDescending { it.remindedTimestamp })
            }
        }
        return HistoryTools(medicineRepository, eventRepository, fixedTime()).definitions()
    }

    private fun taken(id: Int, reminderId: Int, name: String, at: Instant) = event(id, reminderId, name, ReminderStatus.TAKEN, at)

    private suspend fun history(events: List<ReminderEvent>, arguments: String) = tools(events).named("get_intake_history").call(arguments)

    @Test
    fun `should include events from the first second of the first day to the last second of the last day`() = runTest {
        val events = listOf(
            taken(1, 10, "Vitamin X 500 mg", dayStart.minusSeconds(1)),
            taken(2, 10, "Vitamin X 500 mg", dayStart),
            taken(3, 10, "Vitamin X 500 mg", dayStart.plusSeconds(86_399)),
            taken(4, 10, "Vitamin X 500 mg", dayStart.plusSeconds(86_400))
        )

        val result = history(events, """{"from":"2026-10-10","to":"2026-10-10"}""").json()

        assertEquals(listOf(3, 2), result.getValue("events").jsonArray.map { it.jsonObject.getValue("event_id").jsonPrimitive.intOrNull })
        assertEquals(2, result.getValue("total_events").jsonPrimitive.intOrNull)
    }

    @Test
    fun `should list events newest first with their medicine and times in the phone's time zone`() = runTest {
        val events = listOf(
            taken(1, 10, "Vitamin X 500 mg", dayStart.plusSeconds(3600)),
            taken(2, -1, "Medicine A", dayStart.plusSeconds(7200))
        )

        val listed = history(events, """{"from":"2026-10-10","to":"2026-10-10"}""").json().getValue("events").jsonArray.map { it.jsonObject }

        assertEquals(listOf("Medicine A", "Vitamin X 500 mg"), listed.map { it.getValue("medicine_name").jsonPrimitive.content })
        assertEquals(listOf(2, 1), listed.map { it.getValue("medicine_id").jsonPrimitive.intOrNull })
        assertEquals("2026-10-10T01:00:00+02:00", listed[1].getValue("reminded_at").jsonPrimitive.content)
        assertEquals("2026-10-10T01:01:00+02:00", listed[1].getValue("processed_at").jsonPrimitive.content)
    }

    @Test
    fun `should leave the medicine id empty and the processed time empty when they are unknown`() = runTest {
        val events = listOf(event(1, 99, "Deleted medicine", ReminderStatus.RAISED, dayStart.plusSeconds(3600)))

        val listed = history(events, """{"from":"2026-10-10","to":"2026-10-10"}""").json().getValue("events").jsonArray.single().jsonObject

        assertEquals(JsonNull, listed.getValue("medicine_id"))
        assertEquals(JsonNull, listed.getValue("processed_at"))
    }

    @Test
    fun `should only return events of the requested medicine`() = runTest {
        val events = listOf(
            taken(1, 10, "Vitamin X 500 mg", dayStart.plusSeconds(3600)),
            taken(2, 20, "Medicine A", dayStart.plusSeconds(7200))
        )

        val listed = history(events, """{"from":"2026-10-10","to":"2026-10-10","medicine_id":2}""").json().getValue("events").jsonArray

        assertEquals(listOf(2), listed.map { it.jsonObject.getValue("event_id").jsonPrimitive.intOrNull })
    }

    @Test
    fun `should only return events with the requested statuses`() = runTest {
        val events = listOf(
            taken(1, 10, "Vitamin X 500 mg", dayStart.plusSeconds(3600)),
            event(2, 10, "Vitamin X 500 mg", ReminderStatus.SKIPPED, dayStart.plusSeconds(7200))
        )

        val listed = history(events, """{"from":"2026-10-10","to":"2026-10-10","statuses":["skipped"]}""").json().getValue("events").jsonArray

        assertEquals(listOf("SKIPPED"), listed.map { it.jsonObject.getValue("status").jsonPrimitive.content })
    }

    @Test
    fun `should cut the list at 1000 events and say so`() = runTest {
        val events = (1..1001).map { taken(it, 10, "Vitamin X 500 mg", dayStart.plusSeconds(it.toLong())) }

        val result = history(events, """{"from":"2026-10-10","to":"2026-10-10"}""").json()

        assertEquals(1000, result.getValue("events").jsonArray.size)
        assertEquals(1001, result.getValue("total_events").jsonPrimitive.intOrNull)
        assertEquals(true, result.getValue("truncated").jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `should reject an unknown medicine and bad periods with an error`() = runTest {
        val unknown = history(emptyList(), """{"from":"2026-10-10","to":"2026-10-10","medicine_id":99}""")
        val reversed = history(emptyList(), """{"from":"2026-10-11","to":"2026-10-10"}""")
        val tooLong = history(emptyList(), """{"from":"2025-01-01","to":"2026-10-10"}""")
        val missing = history(emptyList(), """{"from":"2026-10-11"}""")

        assertTrue(unknown.text(), unknown.text().contains("medicine_id 99 not found"))
        assertEquals("from must not be after to", reversed.text())
        assertTrue(tooLong.text(), tooLong.text().contains("at most 366 days"))
        assertEquals("to is required", missing.text())
        assertTrue(listOf(unknown, reversed, tooLong, missing).all { it.isError == true })
    }

    @Test
    fun `should accept a period of exactly 366 days`() = runTest {
        val result = history(emptyList(), """{"from":"2025-10-10","to":"2026-10-10"}""")

        assertEquals(null, result.isError)
    }

    @Test
    fun `should work out adherence overall and per medicine`() = runTest {
        // fixedTime() puts "now" at 2026-10-10T12:00:00Z, so the RAISED event at 05:00Z is overdue.
        val events = listOf(
            taken(1, 10, "Vitamin X 500 mg", dayStart.plusSeconds(3600)),
            taken(2, 10, "Vitamin X 500 mg", dayStart.plusSeconds(7200)),
            event(3, 10, "Vitamin X 500 mg", ReminderStatus.SKIPPED, dayStart.plusSeconds(10_800)),
            event(4, 20, "Medicine A", ReminderStatus.RAISED, Instant.parse("2026-10-10T05:00:00Z")),
            taken(5, -1, "Medicine A", dayStart.plusSeconds(14_400))
        )

        val result = tools(events).named("get_adherence_stats").call("""{"from":"2026-10-10","to":"2026-10-10"}""").json()

        val overall = result.getValue("overall").jsonObject
        assertEquals(2, overall.getValue("taken").jsonPrimitive.intOrNull)
        assertEquals(1, overall.getValue("skipped").jsonPrimitive.intOrNull)
        assertEquals(1, overall.getValue("unanswered").jsonPrimitive.intOrNull)
        assertEquals(1, overall.getValue("extra_doses").jsonPrimitive.intOrNull)
        assertEquals(4, overall.getValue("scheduled_total").jsonPrimitive.intOrNull)
        assertEquals(50.0, overall.getValue("adherence_percent").jsonPrimitive.doubleOrNull)
        val perMedicine = result.getValue("per_medicine").jsonArray.map { it.jsonObject }
        assertEquals(listOf("Medicine A", "Vitamin X 500 mg"), perMedicine.map { it.getValue("medicine_name").jsonPrimitive.content })
        assertEquals(0.0, perMedicine[0].getValue("adherence_percent").jsonPrimitive.doubleOrNull)
        assertEquals(66.7, perMedicine[1].getValue("adherence_percent").jsonPrimitive.doubleOrNull)
    }

    @Test
    fun `should have no percentage when there was nothing scheduled`() = runTest {
        val result = tools(emptyList()).named("get_adherence_stats").call("""{"from":"2026-10-10","to":"2026-10-10"}""").json()

        assertEquals(JsonNull, result.getValue("overall").jsonObject.getValue("adherence_percent"))
        assertEquals(0, result.getValue("per_medicine").jsonArray.size)
    }
}
