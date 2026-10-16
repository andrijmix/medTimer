package com.futsch1.medtimer.feature.mcp

import com.futsch1.medtimer.core.domain.model.Medicine
import com.futsch1.medtimer.core.domain.model.MedicineToTag
import com.futsch1.medtimer.core.domain.model.Reminder
import com.futsch1.medtimer.core.domain.model.Tag
import com.futsch1.medtimer.core.domain.repository.MedicineRepository
import com.futsch1.medtimer.core.domain.repository.ReminderRepository
import com.futsch1.medtimer.core.domain.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * An in-memory stand-in for the Room database. Like the real one it has no foreign keys, so deleting a medicine leaves its reminders and tag
 * links behind unless the caller removes them, and a medicine that is read comes back joined with its reminders and tags.
 */
class FakeDatabase {
    val medicines = mutableMapOf<Int, Medicine>()
    val reminders = mutableMapOf<Int, Reminder>()
    val tags = mutableMapOf<Int, Tag>()
    val links = mutableSetOf<Pair<Int, Int>>()

    private var lastId = 0

    private fun nextId() = ++lastId

    private fun joined(medicine: Medicine) = medicine.copy(
        reminders = reminders.values.filter { it.medicineRelId == medicine.id }.sortedBy { it.id },
        tags = links.filter { it.first == medicine.id }.mapNotNull { tags[it.second] }
    )

    fun add(medicine: Medicine, vararg reminders: Reminder): Medicine {
        medicines[medicine.id] = medicine.copy(reminders = emptyList(), tags = emptyList())
        reminders.forEach { this.reminders[it.id] = it.copy(medicineRelId = medicine.id) }
        lastId = maxOf(lastId, medicine.id, *reminders.map { it.id }.toTypedArray())
        return joined(medicines.getValue(medicine.id))
    }

    val medicineRepository = object : MedicineRepository {
        override suspend fun fetch(medicineId: Int) = medicines[medicineId]?.let(::joined)
        override suspend fun getAll() = medicines.values.map(::joined)
        override suspend fun create(medicine: Medicine): Int {
            val id = nextId()
            medicines[id] = medicine.copy(id = id, reminders = emptyList(), tags = emptyList())
            return id
        }

        // Like the real one, an update writes the medicine row only and leaves reminders and tags alone.
        override suspend fun update(medicine: Medicine) {
            medicines[medicine.id] = medicine.copy(reminders = emptyList(), tags = emptyList())
        }

        override suspend fun delete(medicineId: Int) {
            medicines.remove(medicineId)
        }

        override suspend fun getHighestSortOrder() = medicines.values.maxOfOrNull { it.sortOrder } ?: 0.0
        override fun getFlow(medicineId: Int): Flow<Medicine?> = error("not used")
        override fun getAllFlow(): Flow<List<Medicine>> = error("not used")
        override suspend fun updateAll(medicines: List<Medicine>) = error("not used")
        override suspend fun decreaseStock(medicineId: Int, decreaseAmount: Double): Medicine? = error("not used")
        override suspend fun move(id: Int, toPosition: Int) = error("not used")
        override suspend fun deleteAll() = error("not used")
    }

    val reminderRepository = object : ReminderRepository {
        override suspend fun getAll(medicineId: Int) = reminders.values.filter { it.medicineRelId == medicineId }.sortedBy { it.id }
        override suspend fun fetch(reminderId: Int) = reminders[reminderId]
        override suspend fun getLinked(reminderId: Int) = reminders.values.filter { it.linkedReminderId == reminderId }.sortedBy { it.id }
        override suspend fun create(reminder: Reminder): Int {
            val id = nextId()
            reminders[id] = reminder.copy(id = id)
            return id
        }

        override suspend fun update(reminder: Reminder) {
            reminders[reminder.id] = reminder
        }

        override suspend fun updateMany(reminders: List<Reminder>) = reminders.forEach { this@FakeDatabase.reminders[it.id] = it }
        override suspend fun delete(reminderId: Int) {
            reminders.remove(reminderId)
        }

        override fun getAllFlow(medicineId: Int): Flow<List<Reminder>> = error("not used")
        override fun getFlow(reminderId: Int): Flow<Reminder?> = error("not used")
        override suspend fun createMany(reminders: List<Reminder>): List<Int> = error("not used")
        override suspend fun deleteAll() = error("not used")
    }

    val tagRepository = object : TagRepository {
        // Returns the existing id when a tag of this name exists, like the real one.
        override suspend fun create(tag: Tag): Int = tags.values.firstOrNull { it.name == tag.name }?.id ?: nextId().also { tags[it] = tag.copy(id = it) }
        override suspend fun addMedicineTag(medicineId: Int, tagId: Int) {
            links.add(medicineId to tagId)
        }

        override suspend fun removeMedicineTag(medicineId: Int, tagId: Int) {
            links.remove(medicineId to tagId)
        }

        override fun getAllFlow(): Flow<List<Tag>> = flowOf(tags.values.toList())
        override fun getMedicineTagsFlow(): Flow<List<MedicineToTag>> = flowOf(links.map { MedicineToTag(it.first, it.second) })
        override suspend fun getByName(name: String): Tag? = error("not used")
        override suspend fun delete(tag: Tag) = error("not used")
        override fun hasAny(): Flow<Boolean> = error("not used")
        override suspend fun deleteAll() = error("not used")
    }
}
