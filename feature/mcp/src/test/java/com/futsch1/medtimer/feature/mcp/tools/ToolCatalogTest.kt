package com.futsch1.medtimer.feature.mcp.tools

import com.futsch1.medtimer.core.datastore.PreferencesDataSource
import com.futsch1.medtimer.feature.mcp.FakeDatabase
import com.futsch1.medtimer.feature.mcp.domain.MedicineWriter
import com.futsch1.medtimer.feature.mcp.domain.ReminderWriter
import com.futsch1.medtimer.feature.mcp.domain.WriteCoordinator
import com.futsch1.medtimer.feature.mcp.fixedTime
import com.futsch1.medtimer.feature.reminders.api.command.ReminderCommandBus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Invariants that every tool the server offers has to keep, whichever group it comes from.
 */
class ToolCatalogTest {

    private val db = FakeDatabase()
    private val timeAccess = fixedTime()
    private val coordinator = WriteCoordinator(mock<ReminderCommandBus>())
    private val eventRepository = mock<com.futsch1.medtimer.core.domain.repository.ReminderEventRepository>()

    private val all: List<ToolDefinition> =
        MedicineTools(db.medicineRepository, db.tagRepository, timeAccess).definitions() +
            HistoryTools(db.medicineRepository, eventRepository, timeAccess).definitions() +
            ScheduleTools(db.medicineRepository, eventRepository, timeAccess, mock<PreferencesDataSource>(), UnconfinedTestDispatcher()).definitions() +
            MedicineWriteTools(MedicineWriter(db.medicineRepository, db.reminderRepository, db.tagRepository), coordinator, timeAccess).definitions() +
            ReminderWriteTools(ReminderWriter(db.reminderRepository, db.medicineRepository, timeAccess), coordinator, timeAccess).definitions()

    private val readOnlyNames = setOf("list_medicines", "get_medicine", "list_tags", "get_intake_history", "get_adherence_stats", "get_upcoming_doses")

    @Test
    fun `should offer exactly the planned tools`() {
        assertEquals(
            readOnlyNames + setOf("create_medicine", "update_medicine", "delete_medicine", "create_reminder", "update_reminder", "set_reminder_active", "delete_reminder"),
            all.map { it.name }.toSet()
        )
    }

    @Test
    fun `should not use a name twice`() {
        assertEquals(all.size, all.map { it.name }.toSet().size)
    }

    @Test
    fun `should name tools in lower snake case`() {
        assertTrue(all.map { it.name }.toString(), all.all { Regex("[a-z]+(_[a-z]+)*").matches(it.name) })
    }

    @Test
    fun `should describe every tool and every argument for the model`() {
        for (tool in all) {
            assertTrue("${tool.name} needs a description", tool.description.length >= 30)
            assertTrue("${tool.name} needs a title", !tool.annotations.title.isNullOrBlank())
            for ((name, element) in tool.inputSchema.properties.orEmpty()) {
                val schema = element.jsonObject
                assertTrue("${tool.name}.$name needs a type", schema.containsKey("type"))
                assertTrue("${tool.name}.$name needs a description", schema["description"]?.jsonPrimitive?.content.orEmpty().length >= 10)
            }
        }
    }

    @Test
    fun `should only require arguments that the tool has`() {
        for (tool in all) {
            val required = tool.inputSchema.required.orEmpty()
            assertTrue("${tool.name} requires an argument it does not have", tool.inputSchema.properties.orEmpty().keys.containsAll(required))
        }
    }

    @Test
    fun `should declare reading tools as read only and all others as changing`() {
        for (tool in all) {
            assertEquals(tool.name, tool.name in readOnlyNames, tool.annotations.readOnlyHint == true)
            assertEquals(tool.name, false, tool.annotations.openWorldHint)
        }
    }

    @Test
    fun `should declare every tool as an object schema`() {
        assertTrue(all.all { it.inputSchema.type == "object" })
    }
}
