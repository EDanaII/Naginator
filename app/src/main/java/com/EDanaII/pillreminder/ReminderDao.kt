package com.EDanaII.pillreminder

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY id ASC")
    fun observeReminders(): Flow<List<Reminder>>

    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Query("""UPDATE reminders SET name = :name, instructions = :instructions,
        startTimeMillis = :startTimeMillis, doseIntervalHours = :doseIntervalHours,
        nagIntervalMinutes = :nagIntervalMinutes, enabled = :enabled WHERE id = :id""")
    suspend fun update(
        id: Long,
        name: String,
        instructions: String,
        startTimeMillis: Long,
        doseIntervalHours: Int,
        nagIntervalMinutes: Int,
        enabled: Boolean
    ): Int

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("UPDATE reminders SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean): Int
}
