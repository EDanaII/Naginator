package com.EDanaII.pillreminder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.time.Instant

/** Temporary diagnostics only. No reminder database, backup, external storage, or permissions. */
internal object AudioDiagnosticBuffer {
    const val LIMIT = 200
    private val events = setOf("REMINDER_SAVED", "PLAYBACK_REQUEST", "PICKER_RESULT", "PICKER_GRANT",
        "PLAYBACK_BEGIN", "PLAYBACK_EXHAUSTED", "CANDIDATE_ATTEMPT", "CANDIDATE_FAILED",
        "CANDIDATE_PREPARED", "PLAYBACK_DEADLINE", "CANDIDATE_STARTED")
    private val categories = mapOf(
        "selection" to setOf("NULL", "DEFAULT", "CUSTOM"),
        "source" to setOf("CUSTOM", "SYSTEM_DEFAULT", "PACKAGED_FALLBACK"),
        "stage" to setOf("CREATE_PLAYER", "AUDIO_ATTRIBUTES", "SET_DATA_SOURCE", "SET_LOOPING",
            "SET_GAIN", "SET_LISTENERS", "PREPARE_ASYNC", "NATIVE_CUTOFF", "START", "PLAYING"),
        "persistRead" to setOf("SUCCESS", "FAILED"))
    private val booleans = setOf("test", "unlocked", "escalation", "readGrant", "persistableGrant",
        "canceled", "selectionUnchanged")

    // Fail closed if a future caller accidentally supplies private text or adds a new field.
    private fun safe(message: String): Boolean {
        if (message.length > 1024) return false
        val tokens = message.split(' ')
        if (tokens.first() !in events) return false
        return tokens.drop(1).all { token ->
            val pair = token.split('=')
            if (pair.size != 2) return@all false
            val (key, value) = pair
            when {
                key in categories -> value in categories.getValue(key)
                key in booleans -> value == "true" || value == "false"
                key in setOf("id", "level", "mediaErrorWhat", "mediaErrorExtra") -> value.matches(Regex("-?[0-9]{1,19}"))
                key == "gain" -> value in setOf("0.25", "0.5", "0.75", "1.0")
                key == "run" -> value.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
                key == "exception" || key == "causeType" -> value == "NONE" ||
                    value.matches(Regex("(?:java\\.lang|java\\.io|android\\.media)\\.[A-Za-z]+(?:Exception|Error)"))
                else -> false
            }
        }
    }

    private fun <T> database(context: Context, block: (SQLiteDatabase) -> T): T {
        val directory = context.createDeviceProtectedStorageContext().noBackupFilesDir
        return SQLiteDatabase.openOrCreateDatabase(File(directory, "audio_diagnostics.db"), null).use { db ->
            db.rawQuery("PRAGMA busy_timeout=2000", null).use { it.moveToFirst() }
            db.execSQL("CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY, timestamp INTEGER NOT NULL, message TEXT NOT NULL)")
            block(db)
        }
    }

    // Serialize connections within this process; SQLite transactions also serialize other processes.
    @Synchronized fun record(context: Context, message: String) {
        if (!safe(message)) return
        try {
            database(context) { db ->
                db.beginTransaction()
                try {
                    db.execSQL("INSERT INTO events(timestamp, message) VALUES (?, ?)", arrayOf<Any>(System.currentTimeMillis(), message))
                    db.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT $LIMIT)")
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
        } catch (_: Exception) {
            // Diagnostics must never prevent playback, receiver delivery, or reminder operations.
        }
    }

    @Synchronized fun export(context: Context): String = database(context) { db ->
        buildString {
            appendLine("NaginatorAudio diagnostics (latest $LIMIT events; UTC)")
            db.rawQuery("SELECT timestamp, message FROM events ORDER BY id", null).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    val message = cursor.getString(1)
                    if (safe(message)) {
                        appendLine("${Instant.ofEpochMilli(cursor.getLong(0))} $message")
                        count++
                    }
                }
                if (count == 0) appendLine("No audio diagnostic events recorded yet.")
            }
        }
    }

    fun copy(context: Context) {
        val text = export(context)
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Naginator audio diagnostics", text))
    }
}
