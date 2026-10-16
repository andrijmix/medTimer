package com.futsch1.medtimer.feature.mcp.tools

import com.futsch1.medtimer.core.common.time.TimeAccess
import com.futsch1.medtimer.core.domain.model.ReminderEvent
import com.futsch1.medtimer.core.domain.model.ReminderEvent.ReminderStatus
import com.futsch1.medtimer.core.domain.repository.MedicineRepository
import com.futsch1.medtimer.core.domain.repository.ReminderEventRepository
import com.futsch1.medtimer.feature.mcp.domain.AdherenceCalculator
import com.futsch1.medtimer.feature.mcp.domain.AdherenceCounts
import com.futsch1.medtimer.feature.mcp.domain.DateRange
import com.futsch1.medtimer.feature.mcp.domain.MedicineIndex
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

private const val MAX_EVENTS = 1000

/**
 * Tools that read what was taken and when: the intake history and adherence figures derived from it.
 */
class HistoryTools @Inject constructor(
    private val medicineRepository: MedicineRepository,
    private val reminderEventRepository: ReminderEventRepository,
    private val timeAccess: TimeAccess
) {
    fun definitions(): List<ToolDefinition> = listOf(getIntakeHistory(), getAdherenceStats())

    private class Loaded(val events: List<ReminderEvent>, val index: MedicineIndex)

    private fun getIntakeHistory() = defineTool(
        name = "get_intake_history",
        description = "Lists reminder events (taken, skipped, raised) in a period, newest first, at most $MAX_EVENTS. The period is whole days in " +
            "the phone's time zone and may span at most ${DateRange.MAX_DAYS} days.",
        annotations = readOnly("Get intake history"),
        properties = mapOf(
            "from" to stringProp("First day of the period, inclusive, YYYY-MM-DD", format = "date"),
            "to" to stringProp("Last day of the period, inclusive, YYYY-MM-DD", format = "date"),
            "medicine_id" to intProp("Only events of this medicine, from list_medicines"),
            "statuses" to arrayProp(
                "Only events with one of these statuses. Default: all.",
                stringProp("A status", enum = ReminderEvent.statusValuesWithoutDelete.map { it.name })
            )
        ),
        required = listOf("from", "to")
    ) { args ->
        val zone = timeAccess.systemZone()
        val range = args.dateRange(zone)
        val statuses = args.enumListOrNull("statuses", ReminderEvent.statusValuesWithoutDelete) ?: ReminderEvent.statusValuesWithoutDelete
        val loaded = load(range, args.intOrNull("medicine_id"), statuses)
        ToolResults.ok(
            buildJsonObject {
                put("from", range.from.toString())
                put("to", range.to.toString())
                put("total_events", loaded.events.size)
                put("truncated", loaded.events.size > MAX_EVENTS)
                put("events", JsonArray(loaded.events.take(MAX_EVENTS).map { it.toJson(zone, loaded.index.medicineIdOf(it)) }))
            }
        )
    }

    private fun getAdherenceStats() = defineTool(
        name = "get_adherence_stats",
        description = "Adherence in a period, overall and per medicine. taken, skipped and unanswered count the doses that a reminder scheduled; " +
            "unanswered means the reminder was raised and never marked as taken or skipped. adherence_percent is taken divided by all of those, " +
            "or null when there were none. extra_doses are doses logged by hand without a reminder and are not part of the percentage. The " +
            "period is whole days in the phone's time zone and may span at most ${DateRange.MAX_DAYS} days.",
        annotations = readOnly("Get adherence statistics"),
        properties = mapOf(
            "from" to stringProp("First day of the period, inclusive, YYYY-MM-DD", format = "date"),
            "to" to stringProp("Last day of the period, inclusive, YYYY-MM-DD", format = "date"),
            "medicine_id" to intProp("Only this medicine, from list_medicines")
        ),
        required = listOf("from", "to")
    ) { args ->
        val range = args.dateRange(timeAccess.systemZone())
        val loaded = load(range, args.intOrNull("medicine_id"), ReminderEvent.statusValuesWithoutDelete)
        val report = AdherenceCalculator.calculate(loaded.events, timeAccess.now(), loaded.index::medicineIdOf)
        ToolResults.ok(
            buildJsonObject {
                put("from", range.from.toString())
                put("to", range.to.toString())
                put("overall", report.overall.toJson())
                put(
                    "per_medicine",
                    JsonArray(
                        report.perMedicine.map {
                            buildJsonObject {
                                put("medicine_id", it.medicineId)
                                put("medicine_name", it.medicineName)
                                it.counts.toJson().forEach { (key, value) -> put(key, value) }
                            }
                        }
                    )
                )
            }
        )
    }

    private suspend fun load(range: DateRange, medicineId: Int?, statuses: List<ReminderStatus>): Loaded {
        val medicines = medicineRepository.getAll()
        if (medicineId != null && medicines.none { it.id == medicineId }) {
            throw medicineNotFound(medicineId)
        }
        val index = MedicineIndex(medicines)
        // The query returns events strictly after the given instant, so start one second earlier to include the first second of the range.
        val events = reminderEventRepository.getAllFlow(range.start.minusSeconds(1), statuses).first()
            .filter { it.remindedTimestamp in range }
            .filter { medicineId == null || index.medicineIdOf(it) == medicineId }
        return Loaded(events, index)
    }

    private fun ToolArgs.dateRange(zone: ZoneId): DateRange {
        val from = date("from")
        val to = date("to")
        if (from > to) {
            throw ToolArgumentException("from must not be after to")
        }
        if (ChronoUnit.DAYS.between(from, to) >= DateRange.MAX_DAYS) {
            throw ToolArgumentException("The period may span at most ${DateRange.MAX_DAYS} days")
        }
        return DateRange(from, to, zone)
    }

    private fun AdherenceCounts.toJson(): JsonObject = buildJsonObject {
        put("taken", taken)
        put("skipped", skipped)
        put("unanswered", unanswered)
        put("scheduled_total", total)
        put("adherence_percent", adherencePercent)
        put("extra_doses", extraDoses)
    }
}
