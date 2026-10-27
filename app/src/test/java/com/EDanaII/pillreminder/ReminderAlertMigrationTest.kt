package com.EDanaII.pillreminder

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReminderAlertMigrationTest {
    @Test fun versionOneRowsMigrateWithIdentityAnchorsNagIntervalAndDefaultsIntact() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val name = "alert-migration-test.db"
        context.deleteDatabase(name)
        val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        old.execSQL("CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
            "instructions TEXT NOT NULL, startTimeMillis INTEGER NOT NULL, doseIntervalHours INTEGER NOT NULL, " +
            "nagIntervalMinutes INTEGER NOT NULL, enabled INTEGER NOT NULL)")
        old.execSQL("INSERT INTO reminders VALUES (42, 'Existing medicine', 'Existing instructions', 1800000000000, 8, 23, 1)")
        old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        old.execSQL("INSERT INTO room_master_table VALUES (42, '56d1f980ca43c3a7ca05ae037e33d6ee')")
        old.version = 1
        old.close()
        val database = Room.databaseBuilder(context, ReminderDatabase::class.java, name)
            .addMigrations(ReminderDatabase.MIGRATION_1_2).build()
        try {
            val reminder = database.reminderDao().getAll().single()
            assertEquals(Reminder(42, "Existing medicine", "Existing instructions", 1_800_000_000_000,
                8, 23, true), reminder)
            assertNull(reminder.alarmSoundUri)
            assertTrue(reminder.vibrationEnabled)
            assertTrue(reminder.escalationEnabled)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun selectedSoundAndAlertFlagsPersistThroughUpdateAndReopen() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val name = "alert-settings-test.db"
        context.deleteDatabase(name)
        var database = Room.databaseBuilder(context, ReminderDatabase::class.java, name).build()
        val id = database.reminderDao().insert(Reminder(name = "Medicine", instructions = "Instructions",
            startTimeMillis = 1_800_000_000_000, doseIntervalHours = 8, nagIntervalMinutes = 9))
        val edited = database.reminderDao().findById(id)!!.copy(alarmSoundUri = "content://media/selected-tone",
            vibrationEnabled = false, escalationEnabled = false)
        database.reminderDao().update(id, edited.name, edited.instructions, edited.startTimeMillis,
            edited.doseIntervalHours, edited.nagIntervalMinutes, edited.enabled, edited.alarmSoundUri,
            edited.vibrationEnabled, edited.escalationEnabled)
        database.close()
        database = Room.databaseBuilder(context, ReminderDatabase::class.java, name).build()
        try { assertEquals(edited, database.reminderDao().findById(id)) }
        finally { database.close(); context.deleteDatabase(name) }
    }
}
