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
}
