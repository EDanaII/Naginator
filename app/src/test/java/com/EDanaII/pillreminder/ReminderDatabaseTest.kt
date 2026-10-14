package com.EDanaII.pillreminder

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReminderDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: ReminderDatabase
    private val databaseName = "reminder-persistence-test.db"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(databaseName)
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun openDatabase(): ReminderDatabase =
        Room.databaseBuilder(context, ReminderDatabase::class.java, databaseName).build()

    private fun reminder() = Reminder(
        name = "Morning pills",
        instructions = "Pill A — 2 tablets\nTake with food",
        startTimeMillis = 1_800_000_000_000,
        doseIntervalHours = 8,
        nagIntervalMinutes = 15,
        enabled = false
    )

    @Test
    fun newDatabaseIsEmpty() = runBlocking {
        assertTrue(database.reminderDao().observeReminders().first().isEmpty())
    }

    @Test
    fun identicalRemindersReceiveDistinctDatabaseIds() = runBlocking {
        val dao = database.reminderDao()
        val firstId = dao.insert(reminder())
        val secondId = dao.insert(reminder())
        assertTrue(firstId > 0)
        assertTrue(secondId > firstId)
        assertEquals(listOf(firstId, secondId), dao.observeReminders().first().map { it.id })
    }

    @Test
    fun allFieldsSurviveClosingAndReopeningDatabase() = runBlocking {
        val original = reminder()
        val id = database.reminderDao().insert(original)
        database.close()
        database = openDatabase()
        assertEquals(listOf(original.copy(id = id)), database.reminderDao().observeReminders().first())
        val nextId = database.reminderDao().insert(reminder())
        assertTrue(nextId > id)
    }

    private fun reopenDatabase() {
        database.close()
        database = openDatabase()
    }

    @Test
    fun deletePersistsAndLeavesOtherReminderUnchanged() = runBlocking {
        val dao = database.reminderDao()
        val deletedId = dao.insert(reminder())
        val retained = reminder().copy(name = "Evening pills")
        val retainedId = dao.insert(retained)

        assertEquals(1, dao.delete(deletedId))
        reopenDatabase()

        assertEquals(listOf(retained.copy(id = retainedId)),
            database.reminderDao().observeReminders().first())
    }

    @Test
    fun editingPreservesIdentityAndPersistsAllChangedFields() = runBlocking {
        val dao = database.reminderDao()
        val id = dao.insert(reminder())
        val otherId = dao.insert(reminder().copy(name = "Other pills"))
        val edited = reminder().copy(
            id = id,
            name = "Updated pills",
            instructions = "Pill B — 1 tablet",
            startTimeMillis = 1_900_000_000_000,
            doseIntervalHours = 12,
            nagIntervalMinutes = 30,
            enabled = true
        )
        assertEquals(1, dao.update(
            edited.id, edited.name, edited.instructions, edited.startTimeMillis,
            edited.doseIntervalHours, edited.nagIntervalMinutes, edited.enabled
        ))
        reopenDatabase()

        assertEquals(listOf(edited, reminder().copy(id = otherId, name = "Other pills")),
            database.reminderDao().observeReminders().first())
    }

    @Test
    fun enableAndDisablePersistWithoutChangingOtherFields() = runBlocking {
        val original = reminder().copy(enabled = true)
        val id = database.reminderDao().insert(original)
        assertEquals(1, database.reminderDao().setEnabled(id, false))
        reopenDatabase()
        assertEquals(listOf(original.copy(id = id, enabled = false)),
            database.reminderDao().observeReminders().first())

        assertEquals(1, database.reminderDao().setEnabled(id, true))
        reopenDatabase()
        assertEquals(listOf(original.copy(id = id)),
            database.reminderDao().observeReminders().first())
    }

    @Test
    fun defaultEnabledReminderRemainsEnabledOnReopen() = runBlocking {
        val original = Reminder(
            name = "Default enabled",
            instructions = "One pill",
            startTimeMillis = 1_800_000_000_000,
            doseIntervalHours = 8,
            nagIntervalMinutes = 15
        )
        val id = database.reminderDao().insert(original)
        reopenDatabase()
        assertEquals(listOf(original.copy(id = id)),
            database.reminderDao().observeReminders().first())
        assertTrue(database.reminderDao().observeReminders().first().single().enabled)
    }

    @Test
    fun editingDeletedReminderDoesNotRecreateIt() = runBlocking {
        val dao = database.reminderDao()
        val original = reminder()
        val id = dao.insert(original)
        dao.delete(id)
        assertEquals(0, dao.update(
            id, original.name, original.instructions, original.startTimeMillis,
            original.doseIntervalHours, original.nagIntervalMinutes, original.enabled
        ))
        reopenDatabase()
        assertTrue(database.reminderDao().observeReminders().first().isEmpty())
    }
}
