package com.EDanaII.pillreminder

import android.content.Context
import android.os.UserManager
import org.json.JSONObject

/** No medication text belongs here. Room remains the authority for editable definitions. */
internal data class RuntimeReminder(
    val id: Long,
    val start: Long,
    val intervalHours: Int,
    val revision: String,
    val nagMinutes: Int = 0,
    val vibrationEnabled: Boolean = true,
    val escalationEnabled: Boolean = true
) {
    fun genericReminder() = Reminder(id, "Medication reminder",
        "Unlock your phone to view medication details.", start, intervalHours, nagMinutes,
        vibrationEnabled = vibrationEnabled, escalationEnabled = escalationEnabled)

    fun encode(): String = JSONObject().put("id", id).put("start", start)
        .put("interval", intervalHours).put("revision", revision).put("nagMinutes", nagMinutes)
        .put("vibration", vibrationEnabled).put("escalation", escalationEnabled).toString()
}

internal class DirectBootAlarmStore(context: Context) {
    // Keep this context: applicationContext would select credential storage again.
    val preferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun definitions(): List<RuntimeReminder> = preferences.all.filterKeys {
        it.startsWith("definition:")
    }.values.filterIsInstance<String>().mapNotNull { value ->
        try {
            val json = JSONObject(value)
            RuntimeReminder(json.getLong("id"), json.getLong("start"),
                json.getInt("interval"), json.getString("revision"), json.optInt("nagMinutes", 0),
                json.optBoolean("vibration", true), json.optBoolean("escalation", true))
                .takeIf { it.id > 0 && it.intervalHours > 0 && it.revision.isNotEmpty() }
        } catch (_: org.json.JSONException) { null }
    }

    fun definition(id: Long): RuntimeReminder? = definitions().firstOrNull { it.id == id }

    companion object {
        const val FILE_NAME = "direct_boot_alarms"
        fun isUnlocked(context: Context): Boolean =
            context.getSystemService(UserManager::class.java).isUserUnlocked
    }
}
