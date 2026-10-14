package com.EDanaII.pillreminder

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminders")
data class Reminder(
    @field:PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val instructions: String,
    val startTimeMillis: Long,
    val doseIntervalHours: Int,
    val nagIntervalMinutes: Int,
    val enabled: Boolean = true
)