package com.EDanaII.pillreminder

data class Reminder(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val instructions: String,
    val startTimeMillis: Long,
    val doseIntervalHours: Int,
    val nagIntervalMinutes: Int,
    val enabled: Boolean = true
)