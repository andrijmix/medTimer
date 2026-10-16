package com.futsch1.medtimer.feature.mcp.domain

import com.futsch1.medtimer.core.common.helpers.MedicineHelper
import com.futsch1.medtimer.core.domain.model.Medicine
import com.futsch1.medtimer.core.domain.model.Tag
import com.futsch1.medtimer.core.domain.repository.MedicineRepository
import com.futsch1.medtimer.core.domain.repository.ReminderRepository
import com.futsch1.medtimer.core.domain.repository.TagRepository
import com.futsch1.medtimer.feature.mcp.tools.ToolArgumentException
import com.futsch1.medtimer.feature.mcp.tools.medicineNotFound
import java.time.LocalDate
import javax.inject.Inject

/**
 * Changes to a medicine. A null property means "leave as it is"; a date of [LocalDate.EPOCH], which is how the app stores "no date", clears it.
 */
/**
 * How loudly a medicine's reminders announce themselves. These are the three choices of the app's own "Notification priority" setting for a
 * medicine, which the model stores as an importance plus a separate alarm flag.
 */
enum class MedicinePriority(val importance: Medicine.NotificationImportance, val showAsAlarm: Boolean) {
    /** A quiet entry in the notification shade. */
    DEFAULT(Medicine.NotificationImportance.DEFAULT, false),

    /** A banner that pops up on the screen with sound, and shows on the lock screen. */
    HIGH(Medicine.NotificationImportance.HIGH, false),

    /** A full-screen alarm over the lock screen. Needs the permission for full-screen notifications. */
    HIGH_AND_ALARM(Medicine.NotificationImportance.HIGH, true);

    companion object {
        fun of(medicine: Medicine): MedicinePriority = when {
            medicine.notificationImportance == Medicine.NotificationImportance.DEFAULT -> DEFAULT
            medicine.showNotificationAsAlarm -> HIGH_AND_ALARM
            else -> HIGH
        }
    }
}

data class MedicineChanges(
    val name: String? = null,
    val unit: String? = null,
    val stock: Double? = null,
    val refillSize: Double? = null,
    val notes: String? = null,
    val cannotBeSkipped: Boolean? = null,
    val notificationPriority: MedicinePriority? = null,
    val expirationDate: LocalDate? = null,
    val productionDate: LocalDate? = null,
    /** The complete new set of tag names. */
    val tags: List<String>? = null
) {
    val isEmpty: Boolean
        get() = this == MedicineChanges()
}

data class DeletedMedicine(val id: Int, val name: String, val deletedReminderIds: List<Int>)

/**
 * Creates, changes and deletes medicines the way the app's own screens do. Callers run it inside [WriteCoordinator.write].
 */
class MedicineWriter @Inject constructor(
    private val medicineRepository: MedicineRepository,
    private val reminderRepository: ReminderRepository,
    private val tagRepository: TagRepository
) {
    suspend fun create(changes: MedicineChanges): Warned<Medicine> {
        val name = requireNotNull(changes.name) { "A medicine needs a name" }
        val warnings = duplicateNameWarnings(name, exceptMedicineId = null)
        val medicine = Medicine.default().copy(
            name = name,
            // Exactly what the app's "add medicine" dialog does.
            sortOrder = medicineRepository.getHighestSortOrder(),
            unit = changes.unit ?: "",
            amount = changes.stock ?: 0.0,
            refillSize = changes.refillSize ?: 0.0,
            notes = changes.notes ?: "",
            cannotBeSkipped = changes.cannotBeSkipped ?: false,
            notificationImportance = (changes.notificationPriority ?: MedicinePriority.DEFAULT).importance,
            showNotificationAsAlarm = (changes.notificationPriority ?: MedicinePriority.DEFAULT).showAsAlarm,
            expirationDate = changes.expirationDate ?: LocalDate.EPOCH,
            productionDate = changes.productionDate ?: LocalDate.EPOCH
        )
        val medicineId = medicineRepository.create(medicine)
        changes.tags?.let { assignTags(medicineId, current = emptyList(), wanted = it) }
        return Warned(loadOrFail(medicineId), warnings)
    }

    suspend fun update(medicineId: Int, changes: MedicineChanges): Warned<Medicine> {
        val existing = medicineRepository.fetch(medicineId) ?: throw medicineNotFound(medicineId)
        val warnings = changes.name?.let { duplicateNameWarnings(it, exceptMedicineId = medicineId) }.orEmpty()
        medicineRepository.update(
            existing.copy(
                name = changes.name ?: existing.name,
                unit = changes.unit ?: existing.unit,
                amount = changes.stock ?: existing.amount,
                refillSize = changes.refillSize ?: existing.refillSize,
                notes = changes.notes ?: existing.notes,
                cannotBeSkipped = changes.cannotBeSkipped ?: existing.cannotBeSkipped,
                notificationImportance = changes.notificationPriority?.importance ?: existing.notificationImportance,
                showNotificationAsAlarm = changes.notificationPriority?.showAsAlarm ?: existing.showNotificationAsAlarm,
                expirationDate = changes.expirationDate ?: existing.expirationDate,
                productionDate = changes.productionDate ?: existing.productionDate
            )
        )
        // Updating a medicine leaves its tags alone, so they are brought in line separately.
        changes.tags?.let { assignTags(medicineId, current = existing.tags, wanted = it) }
        return Warned(loadOrFail(medicineId), warnings)
    }

    /**
     * Deletes the medicine together with its reminders and tag links. The app's own delete leaves those behind because the database has no
     * foreign keys. The history of taken doses is kept.
     */
    suspend fun delete(medicineId: Int, confirmName: String): Warned<DeletedMedicine> {
        val medicine = medicineRepository.fetch(medicineId) ?: throw medicineNotFound(medicineId)
        if (!medicine.name.trim().equals(confirmName.trim(), ignoreCase = true)) {
            throw ToolArgumentException("confirm_name does not match the name of medicine $medicineId. Check it with get_medicine and ask the user.")
        }
        // Linked reminders belong to the same medicine as their parent, so this is every reminder there is.
        val reminderIds = reminderRepository.getAll(medicineId).map { it.id }
        reminderIds.forEach { reminderRepository.delete(it) }
        medicine.tags.forEach { tagRepository.removeMedicineTag(medicineId, it.id) }
        medicineRepository.delete(medicineId)
        return Warned(DeletedMedicine(medicineId, medicine.name, reminderIds))
    }

    private suspend fun assignTags(medicineId: Int, current: List<Tag>, wanted: List<String>) {
        val wantedNames = wanted.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val kept = current.filter { tag -> wantedNames.any { it.equals(tag.name, ignoreCase = true) } }
        (current - kept.toSet()).forEach { tagRepository.removeMedicineTag(medicineId, it.id) }
        val keptNames = kept.map { it.name.lowercase() }.toSet()
        wantedNames.filter { it.lowercase() !in keptNames }.forEach { name ->
            // Returns the id of the tag with this name if there is one already.
            val tagId = tagRepository.create(Tag(name = name, id = 0))
            tagRepository.addMedicineTag(medicineId, tagId)
        }
    }

    private suspend fun duplicateNameWarnings(name: String, exceptMedicineId: Int?): List<String> =
        medicineRepository.getAll()
            .filter { it.id != exceptMedicineId && MedicineHelper.normalizeMedicineName(it.name).equals(name, ignoreCase = true) }
            .map { "Another medicine is already called \"${it.name}\" (id ${it.id}). Both were kept; tell the user in case this was not intended." }

    private suspend fun loadOrFail(medicineId: Int): Medicine = checkNotNull(medicineRepository.fetch(medicineId)) { "Medicine $medicineId vanished" }
}
