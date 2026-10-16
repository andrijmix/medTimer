package com.futsch1.medtimer.feature.mcp.tools

import com.futsch1.medtimer.core.common.time.TimeAccess
import com.futsch1.medtimer.feature.mcp.domain.MedicineChanges
import com.futsch1.medtimer.feature.mcp.domain.MedicinePriority
import com.futsch1.medtimer.feature.mcp.domain.MedicineWriter
import com.futsch1.medtimer.feature.mcp.domain.WriteCoordinator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import javax.inject.Inject

private const val MAX_NAME_LENGTH = 100
private const val MAX_UNIT_LENGTH = 30
private const val MAX_NOTES_LENGTH = 2000
private const val MAX_TAGS = 20
private const val MAX_TAG_LENGTH = 50

/**
 * Tools that create, change and delete medicines.
 */
class MedicineWriteTools @Inject constructor(
    private val medicineWriter: MedicineWriter,
    private val coordinator: WriteCoordinator,
    private val timeAccess: TimeAccess
) {
    fun definitions(): List<ToolDefinition> = listOf(createMedicine(), updateMedicine(), deleteMedicine())

    private fun fieldProps(clearableDates: Boolean): Map<String, JsonObject> = mapOf(
        "name" to stringProp("Name of the medicine, for example \"Vitamin X 500 mg\""),
        "unit" to stringProp("Unit of the stock, for example \"mg\" or \"tablets\""),
        "stock" to numberProp("Amount in stock, in the unit. Setting it switches on stock management.", minimum = 0.0),
        "refill_size" to numberProp("Amount that one refill adds to the stock", minimum = 0.0),
        "notes" to stringProp("Free text notes"),
        "tags" to arrayProp(
            "All tags the medicine should have. Missing tags are created; tags not listed are removed from this medicine.",
            stringProp("A tag name")
        ),
        "expiration_date" to dateProp("Expiration date, YYYY-MM-DD", clearableDates),
        "production_date" to dateProp("Production date, YYYY-MM-DD", clearableDates),
        "cannot_be_skipped" to boolProp("If true, the reminders of this medicine cannot be marked as skipped"),
        "notification_priority" to stringProp(
            "How the reminders of this medicine announce themselves. DEFAULT: a quiet entry in the notification list. HIGH: a banner that pops " +
                "up on the screen with sound, also on the lock screen. HIGH_AND_ALARM: a full-screen alarm over the lock screen, which needs the " +
                "permission for full-screen notifications. A reminder follows this unless it has a priority of its own.",
            enum = MedicinePriority.entries.map { it.name }
        )
    )

    private fun dateProp(description: String, clearable: Boolean) =
        if (clearable) nullableStringProp("$description. Null removes the date.", format = "date") else stringProp(description, format = "date")

    private fun createMedicine() = defineTool(
        name = "create_medicine",
        description = "Creates a medicine without reminders. Add reminders afterwards with create_reminder. Returns the new medicine with its id.",
        annotations = additive("Create medicine"),
        properties = fieldProps(clearableDates = false),
        required = listOf("name")
    ) { args ->
        val changes = args.medicineChanges(nameRequired = true)
        val zone = timeAccess.systemZone()
        val result = coordinator.write { medicineWriter.create(changes) }
        ToolResults.ok(result.value.toDetailJson(zone), result.warnings)
    }

    private fun updateMedicine() = defineTool(
        name = "update_medicine",
        description = "Changes the given fields of a medicine and leaves the others as they are. Returns the medicine after the change.",
        annotations = destructive("Update medicine"),
        properties = mapOf("medicine_id" to intProp("Id of the medicine, from list_medicines")) + fieldProps(clearableDates = true),
        required = listOf("medicine_id")
    ) { args ->
        val medicineId = args.int("medicine_id")
        val changes = args.medicineChanges(nameRequired = false)
        if (changes.isEmpty) {
            throw ToolArgumentException("Specify at least one field to change")
        }
        val zone = timeAccess.systemZone()
        val result = coordinator.write { medicineWriter.update(medicineId, changes) }
        ToolResults.ok(result.value.toDetailJson(zone), result.warnings)
    }

    private fun deleteMedicine() = defineTool(
        name = "delete_medicine",
        description = "Permanently deletes a medicine together with all its reminders. The history of doses that were taken stays. This cannot " +
            "be undone: ask the user to confirm first.",
        annotations = destructive("Delete medicine"),
        properties = mapOf(
            "medicine_id" to intProp("Id of the medicine, from list_medicines"),
            "confirm_name" to stringProp("The name of the medicine, to make sure that the right one is deleted")
        ),
        required = listOf("medicine_id", "confirm_name")
    ) { args ->
        val medicineId = args.int("medicine_id")
        val confirmName = args.string("confirm_name", MAX_NAME_LENGTH)
        val result = coordinator.write { medicineWriter.delete(medicineId, confirmName) }
        ToolResults.ok(
            buildJsonObject {
                put("deleted_medicine_id", result.value.id)
                put("deleted_medicine_name", result.value.name)
                put("deleted_reminder_ids", JsonArray(result.value.deletedReminderIds.map(::JsonPrimitive)))
            },
            result.warnings
        )
    }

    private fun ToolArgs.medicineChanges(nameRequired: Boolean): MedicineChanges {
        val tags = stringListOrNull("tags", MAX_TAG_LENGTH)
        if (tags != null && tags.size > MAX_TAGS) {
            throw ToolArgumentException("tags may have at most $MAX_TAGS entries")
        }
        return MedicineChanges(
            name = if (nameRequired) string("name", MAX_NAME_LENGTH) else stringOrNull("name", MAX_NAME_LENGTH, allowBlank = false),
            unit = stringOrNull("unit", MAX_UNIT_LENGTH),
            stock = doubleOrNull("stock", min = 0.0),
            refillSize = doubleOrNull("refill_size", min = 0.0),
            notes = stringOrNull("notes", MAX_NOTES_LENGTH),
            cannotBeSkipped = booleanOrNull("cannot_be_skipped"),
            notificationPriority = enumOrNull("notification_priority", MedicinePriority.entries),
            expirationDate = clearableDate("expiration_date"),
            productionDate = clearableDate("production_date"),
            tags = tags
        )
    }

    // The app stores "no date" as the epoch, so that is what an explicit null turns into.
    private fun ToolArgs.clearableDate(name: String): LocalDate? = if (isExplicitNull(name)) LocalDate.EPOCH else dateOrNull(name)
}
