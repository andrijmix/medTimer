package com.futsch1.medtimer.feature.mcp.domain

import com.futsch1.medtimer.core.common.helpers.setRemindersActive
import com.futsch1.medtimer.core.common.time.TimeAccess
import com.futsch1.medtimer.core.domain.model.Reminder
import com.futsch1.medtimer.core.domain.model.ReminderType
import com.futsch1.medtimer.core.domain.repository.MedicineRepository
import com.futsch1.medtimer.core.domain.repository.ReminderRepository
import com.futsch1.medtimer.feature.mcp.tools.ToolArgumentException
import com.futsch1.medtimer.feature.mcp.tools.medicineNotFound
import java.time.LocalDate
import javax.inject.Inject

private fun reminderNotFound(reminderId: Int) = ToolArgumentException("reminder_id $reminderId not found. Use get_medicine to see the reminders of a medicine.")

/**
 * Creates, changes, deletes and pauses reminders the way the app's own screens do. Callers run it inside [WriteCoordinator.write].
 */
class ReminderWriter @Inject constructor(
    private val reminderRepository: ReminderRepository,
    private val medicineRepository: MedicineRepository,
    private val timeAccess: TimeAccess
) {
    /**
     * @param linkedReminderId the reminder that a LINKED reminder follows.
     */
    suspend fun create(medicineId: Int, type: ReminderType, linkedReminderId: Int?, changes: ReminderChanges): Warned<Reminder> {
        val provided = changes.providedFields()
        requireFieldsToFit(type, provided)
        val missing = ReminderFields.required(type) - provided
        if (missing.isNotEmpty()) {
            throw ToolArgumentException("${missing.sorted().joinToString()} ${if (missing.size == 1) "is" else "are"} required for a $type reminder")
        }
        val medicine = medicineRepository.fetch(medicineId) ?: throw medicineNotFound(medicineId)
        val now = timeAccess.now()
        // The starting point is what the app's "add reminder" dialogs start from.
        val base = Reminder.default().copy(
            medicineRelId = medicine.id,
            createdTime = now,
            cycleStartDay = timeAccess.localDate().plusDays(1),
            instructions = ""
        )
        val typed = when (type) {
            ReminderType.TIME_BASED -> base
            ReminderType.CONTINUOUS_INTERVAL -> base.copy(intervalStart = now)
            ReminderType.WINDOWED_INTERVAL -> base.copy(windowedInterval = true)
            ReminderType.LINKED -> base.copy(linkedReminderId = parentOf(medicine.id, linkedReminderId))
            else -> throw ToolArgumentException("A $type reminder cannot be created here")
        }
        val reminder = typed.withChanges(changes)
        requireConsistent(reminder)
        val reminderId = reminderRepository.create(reminder)
        return Warned(checkNotNull(reminderRepository.fetch(reminderId)) { "Reminder $reminderId vanished" })
    }

    suspend fun update(reminderId: Int, changes: ReminderChanges): Warned<Reminder> {
        val existing = reminderRepository.fetch(reminderId) ?: throw reminderNotFound(reminderId)
        requireEditable(existing)
        val provided = changes.providedFields()
        requireFieldsToFit(existing.reminderType, provided)
        if (provided.isEmpty()) {
            throw ToolArgumentException("Specify at least one field to change")
        }
        val updated = existing.withChanges(changes)
        requireConsistent(updated)
        reminderRepository.update(updated)
        return Warned(updated)
    }

    /**
     * Deletes the reminder and the reminders that follow it (and so on), because a linked reminder without its parent would never fire.
     *
     * @return the ids of all deleted reminders, the requested one first.
     */
    suspend fun delete(reminderId: Int): Warned<List<Int>> {
        val existing = reminderRepository.fetch(reminderId) ?: throw reminderNotFound(reminderId)
        requireEditable(existing)
        val deleted = mutableListOf<Int>()
        deleteWithFollowers(existing.id, deleted)
        return Warned(deleted)
    }

    /**
     * Pauses or resumes the given reminders, or all reminders of a medicine, exactly as the app does. Resuming an interval reminder moves its
     * start to today.
     *
     * @return the ids of the affected reminders.
     */
    suspend fun setActive(reminderIds: List<Int>?, medicineId: Int?, active: Boolean): Warned<List<Int>> {
        val reminders = when {
            reminderIds != null -> reminderIds.distinct().map { reminderRepository.fetch(it) ?: throw reminderNotFound(it) }
            medicineId != null -> {
                medicineRepository.fetch(medicineId) ?: throw medicineNotFound(medicineId)
                reminderRepository.getAll(medicineId)
            }

            else -> throw ToolArgumentException("Give either reminder_ids or medicine_id")
        }
        setRemindersActive(reminders, reminderRepository, active)
        return Warned(reminders.map { it.id })
    }

    private suspend fun deleteWithFollowers(reminderId: Int, deleted: MutableList<Int>) {
        deleted.add(reminderId)
        reminderRepository.getLinked(reminderId).forEach { deleteWithFollowers(it.id, deleted) }
        reminderRepository.delete(reminderId)
    }

    private suspend fun parentOf(medicineId: Int, linkedReminderId: Int?): Int {
        val parentId = linkedReminderId ?: throw ToolArgumentException("linked_reminder_id is required for a LINKED reminder")
        val parent = reminderRepository.fetch(parentId) ?: throw ToolArgumentException("linked_reminder_id $parentId not found")
        if (parent.medicineRelId != medicineId) {
            throw ToolArgumentException("linked_reminder_id $parentId belongs to another medicine")
        }
        if (parent.reminderType != ReminderType.TIME_BASED && parent.reminderType != ReminderType.LINKED) {
            throw ToolArgumentException("A reminder can only follow a TIME_BASED or LINKED reminder, not a ${parent.reminderType} one")
        }
        return parentId
    }

    private fun requireEditable(reminder: Reminder) {
        if (reminder.isOutOfStockOrExpirationReminder) {
            throw ToolArgumentException("Reminder ${reminder.id} is a ${reminder.reminderType} reminder that the app manages itself and cannot be changed here")
        }
    }

    private fun requireFieldsToFit(type: ReminderType, provided: Set<String>) {
        val allowed = ReminderFields.allowed(type)
        val misplaced = provided - allowed
        if (misplaced.isNotEmpty()) {
            throw ToolArgumentException(
                "${misplaced.sorted().joinToString()} does not apply to a $type reminder. Its arguments are: ${allowed.sorted().joinToString()}"
            )
        }
    }

    private fun requireConsistent(reminder: Reminder) {
        if (reminder.periodStart != LocalDate.EPOCH && reminder.periodEnd != LocalDate.EPOCH && reminder.periodStart > reminder.periodEnd) {
            throw ToolArgumentException("period_start must not be after period_end")
        }
        // A window that ends before it starts is allowed and runs overnight, but an empty one never fires.
        if (reminder.windowedInterval && reminder.intervalStartTimeOfDay == reminder.intervalEndTimeOfDay) {
            throw ToolArgumentException("window_start and window_end must not be the same time")
        }
    }
}
