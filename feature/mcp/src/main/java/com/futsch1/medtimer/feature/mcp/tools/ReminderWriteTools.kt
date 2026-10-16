package com.futsch1.medtimer.feature.mcp.tools

import com.futsch1.medtimer.core.common.time.TimeAccess
import com.futsch1.medtimer.core.domain.model.Reminder
import com.futsch1.medtimer.core.domain.model.ReminderType
import com.futsch1.medtimer.feature.mcp.domain.ReminderChanges
import com.futsch1.medtimer.feature.mcp.domain.ReminderFields
import com.futsch1.medtimer.feature.mcp.domain.ReminderWriter
import com.futsch1.medtimer.feature.mcp.domain.WriteCoordinator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

private const val MAX_AMOUNT_LENGTH = 50
private const val MAX_INSTRUCTIONS_LENGTH = 500
private const val MAX_CYCLE_DAYS = 365
private const val MAX_INTERVAL_MINUTES = 525_600
private const val MAX_DELAY_MINUTES = 1439

/**
 * Tools that create, change, pause and delete reminders.
 */
class ReminderWriteTools @Inject constructor(
    private val reminderWriter: ReminderWriter,
    private val coordinator: WriteCoordinator,
    private val timeAccess: TimeAccess
) {
    fun definitions(): List<ToolDefinition> = listOf(createReminder(), updateReminder(), setReminderActive(), deleteReminder())

    private fun createReminder() = defineTool(
        name = "create_reminder",
        description = "Creates a reminder for a medicine. TIME_BASED reminds at a time of day (needs time). CONTINUOUS_INTERVAL reminds every " +
            "interval_minutes minutes, counted from interval_start. WINDOWED_INTERVAL reminds every interval_minutes minutes between " +
            "window_start and window_end each day. LINKED reminds delay_minutes after another reminder of the same medicine " +
            "(linked_reminder_id) was confirmed. Every type needs amount. Arguments of another type are refused. The reminder starts active.",
        annotations = additive("Create reminder"),
        properties = mapOf(
            "medicine_id" to intProp("Id of the medicine, from list_medicines"),
            "type" to stringProp("The kind of reminder", enum = ReminderFields.EDITABLE_TYPES.map { it.name }),
            "linked_reminder_id" to intProp("LINKED only: id of the TIME_BASED or LINKED reminder to follow, from get_medicine")
        ) + fieldProps(),
        required = listOf("medicine_id", "type")
    ) { args ->
        val medicineId = args.int("medicine_id")
        val type = args.enum("type", ReminderFields.EDITABLE_TYPES)
        val linkedReminderId = args.intOrNull("linked_reminder_id")
        if (linkedReminderId != null && type != ReminderType.LINKED) {
            throw ToolArgumentException("linked_reminder_id only applies to a LINKED reminder")
        }
        val zone = timeAccess.systemZone()
        val changes = args.reminderChanges(zone)
        val result = coordinator.write { reminderWriter.create(medicineId, type, linkedReminderId, changes) }
        ToolResults.ok(result.value.toJson(zone), result.warnings)
    }

    private fun updateReminder() = defineTool(
        name = "update_reminder",
        description = "Changes the given fields of a reminder and leaves the others as they are. The type of a reminder cannot change; delete it " +
            "and create a new one instead. Only the arguments that apply to the type of the reminder are accepted. Reminders about stock and " +
            "expiry are managed by the app and cannot be changed. Returns the reminder after the change.",
        annotations = destructive("Update reminder"),
        properties = mapOf("reminder_id" to intProp("Id of the reminder, from get_medicine")) + fieldProps(),
        required = listOf("reminder_id")
    ) { args ->
        val reminderId = args.int("reminder_id")
        val zone = timeAccess.systemZone()
        val changes = args.reminderChanges(zone)
        val result = coordinator.write { reminderWriter.update(reminderId, changes) }
        ToolResults.ok(result.value.toJson(zone), result.warnings)
    }

    private fun setReminderActive() = defineTool(
        name = "set_reminder_active",
        description = "Pauses (active false) or resumes (active true) reminders. Give either reminder_ids for specific reminders, or medicine_id " +
            "for all reminders of that medicine. A paused reminder stays but does not fire.",
        annotations = destructive("Pause or resume reminders"),
        properties = mapOf(
            "active" to boolProp("true to resume, false to pause"),
            "reminder_ids" to arrayProp("Ids of the reminders, from get_medicine", intProp("A reminder id")),
            "medicine_id" to intProp("Id of a medicine: all its reminders are affected")
        ),
        required = listOf("active")
    ) { args ->
        val active = args.boolean("active")
        val reminderIds = args.intListOrNull("reminder_ids")
        val medicineId = args.intOrNull("medicine_id")
        if ((reminderIds == null) == (medicineId == null)) {
            throw ToolArgumentException("Give either reminder_ids or medicine_id")
        }
        if (reminderIds != null && reminderIds.isEmpty()) {
            throw ToolArgumentException("reminder_ids must not be empty")
        }
        val result = coordinator.write { reminderWriter.setActive(reminderIds, medicineId, active) }
        ToolResults.ok(
            buildJsonObject {
                put("active", active)
                put("reminder_ids", JsonArray(result.value.map(::JsonPrimitive)))
            },
            result.warnings
        )
    }

    private fun deleteReminder() = defineTool(
        name = "delete_reminder",
        description = "Permanently deletes a reminder and the LINKED reminders that follow it. The history of doses that were taken stays. Ask the " +
            "user to confirm first. To stop a reminder only for a while, use set_reminder_active instead.",
        annotations = destructive("Delete reminder"),
        properties = mapOf("reminder_id" to intProp("Id of the reminder, from get_medicine")),
        required = listOf("reminder_id")
    ) { args ->
        val reminderId = args.int("reminder_id")
        val result = coordinator.write { reminderWriter.delete(reminderId) }
        ToolResults.ok(buildJsonObject { put("deleted_reminder_ids", JsonArray(result.value.map(::JsonPrimitive))) }, result.warnings)
    }

    internal fun fieldProps(): Map<String, JsonObject> = mapOf(
        "amount" to stringProp("The dose as free text in the unit of the medicine, for example \"1\" or \"2.5\""),
        "instructions" to stringProp("Free text shown with the reminder, for example \"after breakfast\""),
        "variable_amount" to boolProp("Ask for the amount every time the reminder is confirmed"),
        "automatically_taken" to boolProp("Mark the dose as taken as soon as the reminder fires"),
        "notification_priority" to stringProp(
            "How this reminder announces itself. SAME_AS_MEDICINE (the default) follows the medicine. DEFAULT: a quiet entry in the notification " +
                "list. HIGH: a banner that pops up with sound, also on the lock screen. HIGH_AND_ALARM: a full-screen alarm over the lock screen, " +
                "which needs the permission for full-screen notifications.",
            enum = Reminder.NotificationImportance.entries.map { it.name }
        ),
        "time" to stringProp("TIME_BASED only: time of day, HH:mm", format = "time"),
        "days" to arrayProp(
            "TIME_BASED only: the weekdays to remind on. An empty list means every day.",
            stringProp("A weekday", enum = DayOfWeek.entries.map { it.name })
        ),
        "consecutive_days" to intProp("TIME_BASED only: with pause_days, the number of days of a cycle on which to remind", 1, MAX_CYCLE_DAYS),
        "pause_days" to intProp("TIME_BASED only: days without reminders after the consecutive days. 0 means no cycle.", 0, MAX_CYCLE_DAYS),
        "cycle_start_day" to stringProp("TIME_BASED only: first day of the cycle, YYYY-MM-DD. Default: tomorrow.", format = "date"),
        "period_start" to nullableStringProp("TIME_BASED only: no reminders before this date, YYYY-MM-DD. Null removes the limit.", format = "date"),
        "period_end" to nullableStringProp("TIME_BASED only: no reminders after this date, YYYY-MM-DD. Null removes the limit.", format = "date"),
        "active_days_of_month" to arrayProp(
            "TIME_BASED only: days of the month to remind on. An empty list means every day.",
            intProp("A day of the month", 1, 31)
        ),
        "interval_minutes" to intProp("CONTINUOUS_INTERVAL and WINDOWED_INTERVAL: minutes between doses", 1, MAX_INTERVAL_MINUTES),
        "interval_start" to stringProp(
            "CONTINUOUS_INTERVAL only: when the first interval starts, as a date and time such as 2026-10-10T08:00 in the phone's time zone. " +
                "Default: now.",
            format = "date-time"
        ),
        "interval_starts_from_processed" to boolProp("CONTINUOUS_INTERVAL only: count the next interval from when the dose was confirmed"),
        "window_start" to stringProp("WINDOWED_INTERVAL only: start of the daily window, HH:mm. Default 08:00.", format = "time"),
        "window_end" to stringProp(
            "WINDOWED_INTERVAL only: end of the daily window, HH:mm. Default 23:00. An end before the start makes the window run overnight.",
            format = "time"
        ),
        "delay_minutes" to intProp("LINKED only: minutes after the linked reminder was confirmed", 0, MAX_DELAY_MINUTES)
    )

    private fun ToolArgs.reminderChanges(zone: ZoneId) = ReminderChanges(
        amount = stringOrNull("amount", MAX_AMOUNT_LENGTH, allowBlank = false),
        instructions = stringOrNull("instructions", MAX_INSTRUCTIONS_LENGTH),
        variableAmount = booleanOrNull("variable_amount"),
        automaticallyTaken = booleanOrNull("automatically_taken"),
        notificationImportance = enumOrNull("notification_priority", Reminder.NotificationImportance.entries),
        time = timeOrNull("time"),
        days = enumListOrNull("days", DayOfWeek.entries)?.distinct(),
        consecutiveDays = intOrNull("consecutive_days", 1..MAX_CYCLE_DAYS),
        pauseDays = intOrNull("pause_days", 0..MAX_CYCLE_DAYS),
        cycleStartDay = dateOrNull("cycle_start_day"),
        periodStart = clearableDate("period_start"),
        periodEnd = clearableDate("period_end"),
        activeDaysOfMonth = intListOrNull("active_days_of_month", 1..31)?.distinct()?.sorted(),
        intervalMinutes = intOrNull("interval_minutes", 1..MAX_INTERVAL_MINUTES),
        intervalStart = instantOrNull("interval_start", zone),
        intervalStartsFromProcessed = booleanOrNull("interval_starts_from_processed"),
        windowStart = timeOrNull("window_start"),
        windowEnd = timeOrNull("window_end"),
        delayMinutes = intOrNull("delay_minutes", 0..MAX_DELAY_MINUTES)
    )

    // The app stores "no date" as the epoch, so that is what an explicit null turns into.
    private fun ToolArgs.clearableDate(name: String): LocalDate? = if (isExplicitNull(name)) LocalDate.EPOCH else dateOrNull(name)
}
