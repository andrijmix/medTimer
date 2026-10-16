package com.futsch1.medtimer.feature.mcp.domain

import com.futsch1.medtimer.core.common.helpers.MedicineHelper
import com.futsch1.medtimer.core.domain.model.ReminderEvent
import com.futsch1.medtimer.core.domain.model.ReminderEvent.ReminderStatus
import com.futsch1.medtimer.core.domain.model.ReminderType
import java.time.Instant

internal data class AdherenceCounts(
    val taken: Int = 0,
    val skipped: Int = 0,
    val unanswered: Int = 0,
    /** Doses that were logged by hand and were not scheduled by a reminder. They do not count towards the adherence percentage. */
    val extraDoses: Int = 0
) {
    /** The scheduled doses: every reminder that was taken, skipped or left unanswered. */
    val total: Int get() = taken + skipped + unanswered

    /** The share of scheduled doses that were taken, in percent with one decimal, or null when there were none. */
    val adherencePercent: Double? get() = if (total == 0) null else Math.round(taken * PERCENT_SCALE / total) / DECIMAL_SCALE

    operator fun plus(other: AdherenceCounts) =
        AdherenceCounts(taken + other.taken, skipped + other.skipped, unanswered + other.unanswered, extraDoses + other.extraDoses)

    private companion object {
        const val PERCENT_SCALE = 1000.0
        const val DECIMAL_SCALE = 10.0
    }
}

internal data class MedicineAdherence(val medicineId: Int?, val medicineName: String, val counts: AdherenceCounts)

internal data class AdherenceReport(val overall: AdherenceCounts, val perMedicine: List<MedicineAdherence>)

/**
 * Turns reminder events into adherence figures. Pure: the caller selects the events of the period.
 *
 * Only reminders for taking a dose count. Reminders about stock and expiry are acknowledged rather than taken, and a refill is not a dose.
 */
internal object AdherenceCalculator {
    private val DOSE_TYPES = setOf(ReminderType.TIME_BASED, ReminderType.LINKED, ReminderType.CONTINUOUS_INTERVAL, ReminderType.WINDOWED_INTERVAL)

    private enum class Outcome { TAKEN, SKIPPED, UNANSWERED, EXTRA }

    private data class GroupKey(val medicineId: Int?, val unresolvedName: String?)

    /**
     * @param now events that were raised before this instant and are still not answered count as unanswered.
     * @param medicineIdOf the medicine an event belongs to, null when unknown; events of an unknown medicine are grouped by name.
     */
    fun calculate(events: List<ReminderEvent>, now: Instant, medicineIdOf: (ReminderEvent) -> Int?): AdherenceReport {
        val groups = mutableMapOf<GroupKey, MutableList<Pair<ReminderEvent, Outcome>>>()
        for (event in events) {
            val outcome = outcomeOf(event, now) ?: continue
            val medicineId = medicineIdOf(event)
            val key = GroupKey(medicineId, if (medicineId == null) MedicineHelper.normalizeMedicineName(event.medicineName) else null)
            groups.getOrPut(key) { mutableListOf() }.add(event to outcome)
        }
        val perMedicine = groups.map { (key, entries) ->
            // Events keep the name the medicine had at the time, so the newest event carries the current one.
            val newest = entries.maxBy { it.first.remindedTimestamp }.first
            MedicineAdherence(key.medicineId, MedicineHelper.normalizeMedicineName(newest.medicineName), countsOf(entries.map { it.second }))
        }.sortedBy { it.medicineName.lowercase() }
        return AdherenceReport(perMedicine.fold(AdherenceCounts()) { sum, entry -> sum + entry.counts }, perMedicine)
    }

    private fun outcomeOf(event: ReminderEvent, now: Instant): Outcome? {
        if (event.reminderType == ReminderType.REFILL) {
            return null
        }
        if (event.reminderId <= 0) {
            return if (event.status == ReminderStatus.TAKEN) Outcome.EXTRA else null
        }
        if (event.reminderType !in DOSE_TYPES) {
            return null
        }
        return when {
            event.status == ReminderStatus.TAKEN -> Outcome.TAKEN
            event.status == ReminderStatus.SKIPPED -> Outcome.SKIPPED
            event.status == ReminderStatus.RAISED && event.remindedTimestamp < now -> Outcome.UNANSWERED
            else -> null
        }
    }

    private fun countsOf(outcomes: List<Outcome>) = AdherenceCounts(
        taken = outcomes.count { it == Outcome.TAKEN },
        skipped = outcomes.count { it == Outcome.SKIPPED },
        unanswered = outcomes.count { it == Outcome.UNANSWERED },
        extraDoses = outcomes.count { it == Outcome.EXTRA }
    )
}
