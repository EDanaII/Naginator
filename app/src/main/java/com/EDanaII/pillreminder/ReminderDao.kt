package com.EDanaII.pillreminder

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY id ASC")
    fun observeReminders(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<Reminder>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun findById(id: Long): Reminder?

    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Query("""UPDATE reminders SET name = :name, instructions = :instructions,
        startTimeMillis = :startTimeMillis, doseIntervalHours = :doseIntervalHours,
        nagIntervalMinutes = :nagIntervalMinutes, enabled = :enabled,
        alarmSoundUri = :alarmSoundUri, vibrationEnabled = :vibrationEnabled,
        escalationEnabled = :escalationEnabled WHERE id = :id""")
    suspend fun update(
        id: Long,
        name: String,
        instructions: String,
        startTimeMillis: Long,
        doseIntervalHours: Int,
        nagIntervalMinutes: Int,
        enabled: Boolean,
        alarmSoundUri: String? = null,
        vibrationEnabled: Boolean = true,
        escalationEnabled: Boolean = true
    ): Int

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("UPDATE reminders SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean): Int
}
