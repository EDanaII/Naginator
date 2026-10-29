package com.EDanaII.pillreminder

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager

/** Observations only: never launches an activity, changes permissions, or alters notifications. */
internal object PresentationDiagnostics {
    private fun <T> read(block: () -> T): T? = try { block() } catch (_: RuntimeException) { null }

    private fun deviceState(context: Context): String =
        "interactive=${read { context.getSystemService(PowerManager::class.java).isInteractive } ?: "unknown"} " +
            "keyguardLocked=${read { context.getSystemService(KeyguardManager::class.java).isKeyguardLocked } ?: "unknown"}"

    private fun identity(occurrence: AlarmOccurrence?): String = occurrence?.let {
        "id=${it.reminderId} test=${it.test} "
    } ?: ""

    fun notification(context: Context, notification: Notification, occurrence: AlarmOccurrence, surface: String) {
        try {
            if (!DeveloperSettings.read(context).detailed) return
            val manager = context.getSystemService(NotificationManager::class.java)
            // Actual saved channel values, not creation defaults. SystemUI ranking may differ.
            val importance = read { manager.getNotificationChannel(AlarmNotifications.CHANNEL_ID)?.importance } ?: -1
            val postedImportance = read { manager.getNotificationChannel(notification.channelId)?.importance } ?: -1
            AlarmSoundDiagnostics.event(context, "PRESENTATION_NOTIFICATION surface=$surface " + identity(occurrence) +
                "fsiAllowed=${read { AlarmPermissions.fullScreenAllowed(context) } ?: "unknown"} " +
                "fsiAttached=${notification.fullScreenIntent != null} importance=$importance " +
                "postedImportance=$postedImportance " + deviceState(context))
        } catch (_: RuntimeException) { /* Diagnostics must not affect alarm delivery. */ }
    }

    fun activity(activity: Activity, lifecycle: String, finishReason: String = "OTHER") {
        try {
            if (!DeveloperSettings.read(activity).detailed) return
            val occurrence = activity.intent?.getStringExtra(AlarmCoordinator.EXTRA_OCCURRENCE)
                ?.let(AlarmCoordinator::decode)
            AlarmSoundDiagnostics.event(activity, "ALARM_ACTIVITY lifecycle=$lifecycle " + identity(occurrence) +
                (if (lifecycle == "FINISH") "finishReason=$finishReason " else "") + deviceState(activity))
        } catch (_: RuntimeException) { /* Diagnostics must not affect activity lifetime. */ }
    }
}
