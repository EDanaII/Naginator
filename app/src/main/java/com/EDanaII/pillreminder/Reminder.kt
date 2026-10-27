package com.EDanaII.pillreminder

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "reminders")
data class Reminder(
    @field:PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val instructions: String,
    val startTimeMillis: Long,
    val doseIntervalHours: Int,
    val nagIntervalMinutes: Int,
    val enabled: Boolean = true,
    val alarmSoundUri: String? = null,
    @ColumnInfo(defaultValue = "1") val vibrationEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "1") val escalationEnabled: Boolean = true
)
