package com.futsch1.medtimer.feature.mcp.tools

import com.futsch1.medtimer.core.common.time.TimeAccess
import com.futsch1.medtimer.core.datastore.PreferencesDataSource
import com.futsch1.medtimer.core.domain.model.Medicine
import com.futsch1.medtimer.core.domain.model.UserPreferences
import com.futsch1.medtimer.core.domain.repository.MedicineRepository
import com.futsch1.medtimer.core.domain.repository.ReminderEventRepository
import com.futsch1.medtimer.feature.mcp.call
import com.futsch1.medtimer.feature.mcp.json
import com.futsch1.medtimer.feature.mcp.medicine
import com.futsch1.medtimer.feature.mcp.named
import com.futsch1.medtimer.feature.mcp.reminder
import com.futsch1.medtimer.feature.mcp.text
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleToolsTest {

    // The simulation starts on the day the time source reports. The epoch is the day the scheduler's own tests use.
    private val timeAccess: TimeAccess = mock {
        on { systemZone() } doReturn ZoneOffset.UTC
        on { localDate() } doReturn LocalDate.EPOCH
        on { now() } doReturn Instant.EPOCH
    }

    private fun tools(medicines: List<Medicine>): List<ToolDefinition> {
        val medicineRepository = mock<MedicineRepository> { onBlocking { getAll() } doReturn medicines }
        val eventRepository = mock<ReminderEventRepository> { onBlocking { getForScheduling(any()) } doReturn emptyList() }
        val preferences = mock<PreferencesDataSource>()
        whenever(preferences.preferences).thenReturn(MutableStateFlow(UserPreferences.default()))
        return ScheduleTools(medicineRepository, eventRepository, timeAccess, preferences, UnconfinedTestDispatcher()).definitions()
    }

    private val vitamin = medicine(1, "Vitamin X 500 mg", listOf(reminder(10, 1)), amount = 2.0)
    private val medicineA = medicine(2, "Medicine A", listOf(reminder(20, 2)))

    @Test
    fun `should list the doses of the coming days in time order`() = runTest {
        val result = tools(listOf(vitamin)).named("get_upcoming_doses").call("""{"days":3}""").json()

        val doses = result.getValue("doses").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("1970-01-01T08:00:00Z", "1970-01-02T08:00:00Z", "1970-01-03T08:00:00Z"),
            doses.map { it.getValue("at").jsonPrimitive.content }
        )
        assertEquals(setOf(1), doses.map { it.getValue("medicine_id").jsonPrimitive.intOrNull }.toSet())
        assertEquals("Vitamin X 500 mg", doses.first().getValue("medicine_name").jsonPrimitive.content)
        assertEquals("1", doses.first().getValue("amount").jsonPrimitive.content)
        assertEquals("1970-01-01", result.getValue("from").jsonPrimitive.content)
        assertEquals("1970-01-03", result.getValue("through").jsonPrimitive.content)
        assertEquals(false, result.getValue("truncated").jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `should show the stock after each dose and the day the stock runs out`() = runTest {
        val result = tools(listOf(vitamin)).named("get_upcoming_doses").call("""{"days":3}""").json()

        val doses = result.getValue("doses").jsonArray.map { it.jsonObject }
        assertEquals(1.0, doses[0].getValue("stock_after").jsonPrimitive.doubleOrNull)
        assertEquals(0.0, doses[1].getValue("stock_after").jsonPrimitive.doubleOrNull)
        val runOut = result.getValue("stock_run_out").jsonArray.single().jsonObject
        assertEquals(1, runOut.getValue("medicine_id").jsonPrimitive.intOrNull)
        assertEquals("1970-01-02", runOut.getValue("date").jsonPrimitive.content)
    }

    @Test
    fun `should not show a stock for a medicine without stock management`() = runTest {
        val result = tools(listOf(medicineA)).named("get_upcoming_doses").call("""{"days":1}""").json()

        assertEquals(JsonNull, result.getValue("doses").jsonArray.single().jsonObject.getValue("stock_after"))
        assertEquals(0, result.getValue("stock_run_out").jsonArray.size)
    }

    @Test
    fun `should only list the doses of the requested medicine`() = runTest {
        val result = tools(listOf(vitamin, medicineA)).named("get_upcoming_doses").call("""{"days":2,"medicine_id":2}""").json()

        val doses = result.getValue("doses").jsonArray.map { it.jsonObject }
        assertEquals(2, doses.size)
        assertEquals(setOf(2), doses.map { it.getValue("medicine_id").jsonPrimitive.intOrNull }.toSet())
    }

    @Test
    fun `should look seven days ahead by default`() = runTest {
        val result = tools(listOf(medicineA)).named("get_upcoming_doses").call().json()

        assertEquals(7, result.getValue("doses").jsonArray.size)
        assertEquals("1970-01-07", result.getValue("through").jsonPrimitive.content)
    }

    @Test
    fun `should reject days outside 1 to 31 and an unknown medicine`() = runTest {
        val tools = tools(listOf(vitamin))

        val tooFew = tools.named("get_upcoming_doses").call("""{"days":0}""")
        val tooMany = tools.named("get_upcoming_doses").call("""{"days":32}""")
        val unknown = tools.named("get_upcoming_doses").call("""{"medicine_id":99}""")

        assertEquals("days must be between 1 and 31, got 0", tooFew.text())
        assertEquals("days must be between 1 and 31, got 32", tooMany.text())
        assertTrue(unknown.text(), unknown.text().contains("medicine_id 99 not found"))
        assertTrue(listOf(tooFew, tooMany, unknown).all { it.isError == true })
    }
}
