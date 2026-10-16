package com.futsch1.medtimer.feature.mcp.domain

import com.futsch1.medtimer.core.common.helpers.MedicineHelper
import com.futsch1.medtimer.core.domain.model.Medicine
import com.futsch1.medtimer.core.domain.model.ReminderEvent

/**
 * Finds the medicine a reminder event belongs to. Events only store the id of the reminder and the medicine's name at the time, so the
 * link is lost when a reminder is deleted and never existed for doses that were logged by hand.
 */
internal class MedicineIndex(medicines: List<Medicine>) {
    private val medicineIdByReminderId: Map<Int, Int> =
        medicines.flatMap { medicine -> medicine.reminders.map { it.id to medicine.id } }.toMap()

    private val medicineIdsByName: Map<String, List<Int>> =
        medicines.groupBy({ MedicineHelper.normalizeMedicineName(it.name) }, { it.id })

    /**
     * The id of the event's medicine, or null when it cannot be told: its reminder is gone and the name matches no medicine or several.
     */
    fun medicineIdOf(event: ReminderEvent): Int? =
        medicineIdByReminderId[event.reminderId]
            ?: medicineIdsByName[MedicineHelper.normalizeMedicineName(event.medicineName)]?.singleOrNull()
}
