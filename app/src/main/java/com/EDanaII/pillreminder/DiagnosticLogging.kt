package com.EDanaII.pillreminder

import android.content.Context

/** Essential failures and recovery remain available without detailed tracing. */
internal object DiagnosticLogging {
    fun retained(context: Context, message: String): String? {
        if (DeveloperSettings.read(context).detailed) return message
        val tokens = message.split(' ')
        val event = tokens.first()
        val essential = event.startsWith("DIAGNOSTIC_") || event in setOf("PLAYBACK_FAILURE", "PLAYBACK_EXHAUSTED", "CANDIDATE_FAILED", "CANDIDATE_STARTED",
            "CANDIDATE_TIMEOUT", "FALLBACK_POST", "FALLBACK_CANCEL", "FALLBACK_SOUND", "PLAYBACK_STOP") ||
            "status=FAILED" in tokens ||
            (event == "FOCUS_REQUEST" && "result=0" in tokens) ||
            (event == "REGISTRATION" && "status=REGISTERED" in tokens) ||
            (event == "AUDIO_OWNER" && "owner=APPLICATION" !in tokens)
        if (!essential) return null
        // Strip timing/process traces from routine mode, retaining correlation and outcome.
        val fields = setOf("id", "test", "atMs", "run", "status", "reason", "owner", "source",
            "stage", "result", "exception", "causeType", "mediaErrorWhat", "mediaErrorExtra", "kind",
            "soundEnabled")
        return (listOf(event) + tokens.drop(1).filter {
            val key = it.substringBefore('=')
            key in fields || (key == "importance" && (event == "FALLBACK_POST" || event.startsWith("DIAGNOSTIC_")))
        }).joinToString(" ")
    }
}
