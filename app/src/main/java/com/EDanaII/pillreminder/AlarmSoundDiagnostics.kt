package com.EDanaII.pillreminder

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import java.util.UUID

/** Only categories, numeric identifiers and exception types: never URI or exception text. */
internal object AlarmSoundDiagnostics {
    const val TAG = "NaginatorAudio"
    fun nextRun(): String = UUID.randomUUID().toString()
    fun selection(uri: String?): String = when {
        uri.isNullOrBlank() -> "NULL"
        RingtoneManager.isDefault(Uri.parse(uri)) -> "DEFAULT"
        else -> "CUSTOM"
    }
    fun event(context: Context, message: String) {
        val retained = DiagnosticLogging.retained(context, message) ?: return
        Log.i(TAG, retained)
        AudioDiagnosticBuffer.record(context, retained)
    }
    fun failure(context: Context, message: String, exception: Exception) {
        // Exception messages and causes can contain content URIs and private filenames.
        val sanitized = "$message exception=${exception.javaClass.name} causeType=${exception.cause?.javaClass?.name ?: "NONE"}"
        val retained = DiagnosticLogging.retained(context, sanitized) ?: return
        Log.w(TAG, retained)
        AudioDiagnosticBuffer.record(context, retained)
    }
}
