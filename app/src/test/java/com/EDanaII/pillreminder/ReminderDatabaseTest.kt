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
}
