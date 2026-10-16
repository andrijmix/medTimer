package com.futsch1.medtimer.feature.mcp.tools

import com.futsch1.medtimer.core.common.time.TimeAccess
import com.futsch1.medtimer.core.domain.repository.MedicineRepository
import com.futsch1.medtimer.core.domain.repository.TagRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

internal fun medicineNotFound(medicineId: Int) = ToolArgumentException("medicine_id $medicineId not found. Use list_medicines to see the valid ids.")

/**
 * Tools that read medicines and tags.
 */
class MedicineTools @Inject constructor(
    private val medicineRepository: MedicineRepository,
    private val tagRepository: TagRepository,
    private val timeAccess: TimeAccess
) {
    fun definitions(): List<ToolDefinition> = listOf(listMedicines(), getMedicine(), listTags())

    private fun listMedicines() = defineTool(
        name = "list_medicines",
        description = "Lists all medicines with id, name, unit, stock, tags and the number of reminders. Stock is null when the medicine has no " +
            "stock management. Use the ids with the other tools.",
        annotations = readOnly("List medicines")
    ) {
        val medicines = medicineRepository.getAll().sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
        ToolResults.ok(buildJsonObject { put("medicines", JsonArray(medicines.map { it.toSummaryJson() })) })
    }

    private fun getMedicine() = defineTool(
        name = "get_medicine",
        description = "Gets one medicine with all its details and all its reminders. Reminders of type OUT_OF_STOCK and EXPIRATION_DATE are " +
            "created by the app and have editable_via_mcp set to false.",
        annotations = readOnly("Get medicine"),
        properties = mapOf("medicine_id" to intProp("Id of the medicine, from list_medicines")),
        required = listOf("medicine_id")
    ) { args ->
        val medicineId = args.int("medicine_id")
        val medicine = medicineRepository.fetch(medicineId) ?: throw medicineNotFound(medicineId)
        ToolResults.ok(medicine.toDetailJson(timeAccess.systemZone()))
    }

    private fun listTags() = defineTool(
        name = "list_tags",
        description = "Lists all tags and the ids of the medicines that carry each tag.",
        annotations = readOnly("List tags")
    ) {
        val tags = tagRepository.getAllFlow().first()
        val links = tagRepository.getMedicineTagsFlow().first()
        ToolResults.ok(
            buildJsonObject {
                put(
                    "tags",
                    JsonArray(
                        tags.sortedBy { it.name.lowercase() }.map { tag ->
                            buildJsonObject {
                                put("id", tag.id)
                                put("name", tag.name)
                                put("medicine_ids", JsonArray(links.filter { it.tagId == tag.id }.map { JsonPrimitive(it.medicineId) }))
                            }
                        }
                    )
                )
            }
        )
    }
}
