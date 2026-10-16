package com.futsch1.medtimer.feature.mcp.tools

import com.futsch1.medtimer.core.common.di.Dispatcher
import com.futsch1.medtimer.core.common.di.MedTimerDispatchers
import com.futsch1.medtimer.core.common.time.TimeAccess
import com.futsch1.medtimer.core.datastore.PreferencesDataSource
import com.futsch1.medtimer.core.domain.model.SimulatedReminder
import com.futsch1.medtimer.core.domain.repository.MedicineRepository
import com.futsch1.medtimer.core.domain.repository.ReminderEventRepository
import com.futsch1.medtimer.feature.reminders.api.scheduling.SchedulingSimulator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val DEFAULT_DAYS = 7
private const val MAX_DAYS = 31
private const val MAX_DOSES = 500
private val SIMULATION_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(2)

/**
 * Tools that look ahead: which doses are due and when a medicine runs out.
 */
class ScheduleTools @Inject constructor(
    private val medicineRepository: MedicineRepository,
    private val reminderEventRepository: ReminderEventRepository,
    private val timeAccess: TimeAccess,
    private val preferencesDataSource: PreferencesDataSource,
    @param:Dispatcher(MedTimerDispatchers.Default) private val defaultDispatcher: CoroutineDispatcher
) {
    fun definitions(): List<ToolDefinition> = listOf(getUpcomingDoses())

    private fun getUpcomingDoses() = defineTool(
        name = "get_upcoming_doses",
        description = "Lists the doses that the active reminders schedule for the next days, in time order, and the date on which each medicine " +
            "with stock management runs out within that time. The list is capped at $MAX_DOSES doses; truncated says so.",
        annotations = readOnly("Get upcoming doses"),
        properties = mapOf(
            "days" to intProp("Number of days to look ahead, starting today. Default $DEFAULT_DAYS.", minimum = 1, maximum = MAX_DAYS),
            "medicine_id" to intProp("Only this medicine, from list_medicines")
        )
    ) { args ->
        ToolResults.ok(upcoming(args.intOrNull("days", 1..MAX_DAYS) ?: DEFAULT_DAYS, args.intOrNull("medicine_id")))
    }

    private suspend fun upcoming(days: Int, medicineId: Int?): JsonObject = withContext(defaultDispatcher) {
        val allMedicines = medicineRepository.getAll()
        if (medicineId != null && allMedicines.none { it.id == medicineId }) {
            throw medicineNotFound(medicineId)
        }
        val medicines = if (medicineId == null) allMedicines else allMedicines.filter { it.id == medicineId }
        val events = reminderEventRepository.getForScheduling(medicines)
        val zone = timeAccess.systemZone()
        val endDay = timeAccess.localDate().plusDays(days.toLong())

        val doses = mutableListOf<SimulatedReminder>()
        val runOutDates = mutableMapOf<Int, LocalDate>()
        var truncated = false
        val startedAt = System.nanoTime()
        SchedulingSimulator(medicines, events, timeAccess, preferencesDataSource).simulate { simulated, date ->
            if (date >= endDay) {
                return@simulate false
            }
            val medicine = simulated.scheduledReminder.medicine
            doses.add(simulated)
            if (medicine.isStockManagementActive() && simulated.stockAfter == 0.0) {
                runOutDates.putIfAbsent(medicine.id, date)
            }
            truncated = doses.size >= MAX_DOSES || System.nanoTime() - startedAt > SIMULATION_BUDGET_NANOS
            !truncated
        }

        buildJsonObject {
            put("from", timeAccess.localDate().toString())
            put("through", endDay.minusDays(1).toString())
            put("truncated", truncated)
            put("doses", JsonArray(doses.map { it.toJson(zone) }))
            put(
                "stock_run_out",
                JsonArray(
                    runOutDates.map { (id, date) ->
                        buildJsonObject {
                            put("medicine_id", id)
                            put("medicine_name", medicines.first { it.id == id }.name)
                            put("date", date.toString())
                        }
                    }
                )
            )
        }
    }

    private fun SimulatedReminder.toJson(zone: java.time.ZoneId): JsonObject = buildJsonObject {
        val medicine = scheduledReminder.medicine
        put("at", scheduledReminder.timestamp.toIsoString(zone))
        put("medicine_id", medicine.id)
        put("medicine_name", medicine.name)
        put("reminder_id", scheduledReminder.reminder.id)
        put("amount", scheduledReminder.reminder.amount)
        put("stock_after", if (medicine.isStockManagementActive()) JsonPrimitive(stockAfter) else JsonNull)
    }
}
