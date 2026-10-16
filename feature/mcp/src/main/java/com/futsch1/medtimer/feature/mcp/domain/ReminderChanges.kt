package com.futsch1.medtimer.feature.mcp.domain

import com.futsch1.medtimer.core.domain.model.Reminder
import com.futsch1.medtimer.core.domain.model.ReminderTime
import com.futsch1.medtimer.core.domain.model.ReminderType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Changes to a reminder. A null property means "leave as it is"; a date of [LocalDate.EPOCH], which is how the app stores "no date", clears it.
 * Which properties make sense depends on the type of reminder, see [ReminderFields].
 */
data class ReminderChanges(
    val amount: String? = null,
    val instructions: String? = null,
    val variableAmount: Boolean? = null,
    val automaticallyTaken: Boolean? = null,
    val notificationImportance: Reminder.NotificationImportance? = null,
    // Time-based reminders
    val time: LocalTime? = null,
    val days: List<DayOfWeek>? = null,
    val consecutiveDays: Int? = null,
    val pauseDays: Int? = null,
    val cycleStartDay: LocalDate? = null,
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
    val activeDaysOfMonth: List<Int>? = null,
    // Interval reminders
    val intervalMinutes: Int? = null,
    val intervalStart: Instant? = null,
    val intervalStartsFromProcessed: Boolean? = null,
    val windowStart: LocalTime? = null,
    val windowEnd: LocalTime? = null,
    // Linked reminders
    val delayMinutes: Int? = null
) {
    /** The tool argument names of the properties that are set, to compare with [ReminderFields.allowed]. */
    fun providedFields(): Set<String> = listOf(
        "amount" to amount, "instructions" to instructions, "variable_amount" to variableAmount, "automatically_taken" to automaticallyTaken,
        "notification_priority" to notificationImportance, "time" to time, "days" to days, "consecutive_days" to consecutiveDays, "pause_days" to pauseDays, "cycle_start_day" to cycleStartDay,
        "period_start" to periodStart, "period_end" to periodEnd, "active_days_of_month" to activeDaysOfMonth,
        "interval_minutes" to intervalMinutes, "interval_start" to intervalStart,
        "interval_starts_from_processed" to intervalStartsFromProcessed, "window_start" to windowStart, "window_end" to windowEnd,
        "delay_minutes" to delayMinutes
    ).filter { it.second != null }.mapTo(mutableSetOf()) { it.first }
}

/**
 * The tool arguments that apply to each type of reminder, so that an argument which would be ignored is reported instead.
 */
object ReminderFields {
    val ALL: Set<String> = setOf(
        "amount", "instructions", "variable_amount", "automatically_taken", "notification_priority", "time", "days", "consecutive_days", "pause_days", "cycle_start_day",
        "period_start", "period_end", "active_days_of_month", "interval_minutes", "interval_start", "interval_starts_from_processed",
        "window_start", "window_end", "delay_minutes"
    )

    private val COMMON = setOf("amount", "instructions", "variable_amount", "automatically_taken", "notification_priority")

    /** The types of reminder that can be created and changed through MCP. */
    val EDITABLE_TYPES: List<ReminderType> =
        listOf(ReminderType.TIME_BASED, ReminderType.CONTINUOUS_INTERVAL, ReminderType.WINDOWED_INTERVAL, ReminderType.LINKED)

    fun allowed(type: ReminderType): Set<String> = when (type) {
        ReminderType.TIME_BASED ->
            COMMON + setOf("time", "days", "consecutive_days", "pause_days", "cycle_start_day", "period_start", "period_end", "active_days_of_month")

        ReminderType.CONTINUOUS_INTERVAL -> COMMON + setOf("interval_minutes", "interval_start", "interval_starts_from_processed")
        ReminderType.WINDOWED_INTERVAL -> COMMON + setOf("interval_minutes", "window_start", "window_end")
        ReminderType.LINKED -> COMMON + "delay_minutes"
        else -> emptySet()
    }

    /** What has to be given when a reminder of this type is created; the app has no sensible default for these. */
    fun required(type: ReminderType): Set<String> = when (type) {
        ReminderType.TIME_BASED -> setOf("amount", "time")
        ReminderType.CONTINUOUS_INTERVAL, ReminderType.WINDOWED_INTERVAL -> setOf("amount", "interval_minutes")
        ReminderType.LINKED -> setOf("amount", "delay_minutes")
        else -> emptySet()
    }
}

internal fun Reminder.withChanges(changes: ReminderChanges): Reminder = copy(
    amount = changes.amount ?: amount,
    instructions = changes.instructions ?: instructions,
    variableAmount = changes.variableAmount ?: variableAmount,
    automaticallyTaken = changes.automaticallyTaken ?: automaticallyTaken,
    notificationImportance = changes.notificationImportance ?: notificationImportance,
    // The same field holds a time of day, an interval and a delay; which one it is follows from the type of the reminder.
    time = when {
        changes.time != null -> ReminderTime(changes.time)
        changes.intervalMinutes != null -> ReminderTime(changes.intervalMinutes, isDuration = true)
        changes.delayMinutes != null -> ReminderTime(changes.delayMinutes, isDuration = true)
        else -> time
    },
    days = changes.days ?: days,
    consecutiveDays = changes.consecutiveDays ?: consecutiveDays,
    pauseDays = changes.pauseDays ?: pauseDays,
    cycleStartDay = changes.cycleStartDay ?: cycleStartDay,
    periodStart = changes.periodStart ?: periodStart,
    periodEnd = changes.periodEnd ?: periodEnd,
    activeDaysOfMonth = changes.activeDaysOfMonth ?: activeDaysOfMonth,
    intervalStart = changes.intervalStart ?: intervalStart,
    intervalStartsFromProcessed = changes.intervalStartsFromProcessed ?: intervalStartsFromProcessed,
    intervalStartTimeOfDay = changes.windowStart ?: intervalStartTimeOfDay,
    intervalEndTimeOfDay = changes.windowEnd ?: intervalEndTimeOfDay
)
