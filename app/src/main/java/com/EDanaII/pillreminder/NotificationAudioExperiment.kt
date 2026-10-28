package com.EDanaII.pillreminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import java.util.UUID

/** Removable, one-shot experiment. Never enters the medication coordinator or playback service. */
internal object NotificationAudioExperiment {
    const val CHANNEL_ID = "diagnostic_notification_alarm_v1"
    const val FIRE = "com.EDanaII.pillreminder.DIAGNOSTIC_NOTIFICATION_FIRE"
    const val DISMISS = "com.EDanaII.pillreminder.DIAGNOSTIC_NOTIFICATION_DISMISS"
    private const val TAG = "notification-audio-experiment"
    private const val TOKEN = "diagnostic_token"
    private const val DURATION_MS = 30_000L

    private fun preferences(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(TAG, Context.MODE_PRIVATE)

    private fun operation(context: Context, action: String, token: String) = PendingIntent.getBroadcast(
        context, 481, Intent(context, MedicationAlarmReceiver::class.java).setAction(action)
            .apply { if (action == DISMISS) data = Uri.parse("pillreminder://diagnostic-dismiss/$token") }
            .putExtra(TOKEN, token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun channel(context: Context): NotificationChannel {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            "Diagnostic: system alarm audio", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "One-shot notification audio experiment; not medication reminders"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(false)
        })
        return manager.getNotificationChannel(CHANNEL_ID)
    }

    @Synchronized fun schedule(context: Context, now: Long = System.currentTimeMillis()): String {
        return try {
            val channel = channel(context)
            if (!AlarmPermissions.notificationsAllowed(context) ||
                channel.importance < NotificationManager.IMPORTANCE_DEFAULT || channel.sound == null ||
                !AlarmPermissions.exactAlarmsAllowed(context)) {
                event(context, "DIAGNOSTIC_SCHEDULE status=FAILED importance=${channel.importance} soundEnabled=${channel.sound != null}")
                return "Diagnostic not scheduled. Check notifications, diagnostic channel sound, and exact-alarm access."
            }
            cancel(context, reason = "REPLACED")
            val token = UUID.randomUUID().toString()
            check(preferences(context).edit().putString(TOKEN, token).putBoolean("pending", true).commit())
            val at = now + 60_000L
            context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, at, operation(context, FIRE, token))
            event(context, "DIAGNOSTIC_SCHEDULE status=REGISTERED atMs=$at importance=${channel.importance} soundEnabled=${channel.sound != null}")
            "Diagnostic notification scheduled in 1 minute. Leave the app or lock the screen. Sound plays once; notification expires after 30 seconds."
        } catch (_: RuntimeException) {
            cancel(context, reason = "CANCELED")
            event(context, "DIAGNOSTIC_SCHEDULE status=FAILED")
            "Could not schedule the diagnostic notification."
        }
    }

    @Synchronized fun cancel(context: Context, token: String? = null, reason: String = "CANCELED") {
        val preferences = preferences(context)
        val current = preferences.getString(TOKEN, null) ?: return
        if (token != null && token != current) return
        check(preferences.edit().clear().commit())
        context.getSystemService(AlarmManager::class.java).cancel(operation(context, FIRE, current))
        context.getSystemService(NotificationManager::class.java).cancel(TAG, 481)
        event(context, "DIAGNOSTIC_CANCEL reason=$reason")
    }

    @Synchronized fun receive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(TOKEN) ?: return
        val preferences = preferences(context)
        if (preferences.getString(TOKEN, null) != token) return
        if (intent.action == DISMISS) {
            cancel(context, token, "DISMISSED")
            return
        }
        if (intent.action != FIRE || !preferences.getBoolean("pending", false)) return
        // Consume before posting: duplicate deliveries cannot alert again.
        check(preferences.edit().putBoolean("pending", false).commit())
        try {
            val channel = channel(context)
            if (!AlarmPermissions.notificationsAllowed(context)) {
                event(context, "DIAGNOSTIC_POST status=FAILED importance=${channel.importance} soundEnabled=${channel.sound != null}")
                return
            }
            val dismiss = operation(context, DISMISS, token)
            val notification = Notification.Builder(context, CHANNEL_ID)
                .withNaggyIcons(context)
                .setContentTitle("Diagnostic: Android-managed alarm audio")
                .setContentText("System alarm sound, once. Not a medication reminder.")
                .setCategory(Notification.CATEGORY_ALARM)
                .setTimeoutAfter(DURATION_MS)
                .setDeleteIntent(dismiss)
                .addAction(Notification.Action.Builder(null, "Dismiss diagnostic", dismiss).build())
                .build()
            context.getSystemService(NotificationManager::class.java).notify(TAG, 481, notification)
            event(context, "DIAGNOSTIC_POST status=ACCEPTED importance=${channel.importance} soundEnabled=${channel.sound != null}")
        } catch (_: RuntimeException) {
            event(context, "DIAGNOSTIC_POST status=FAILED")
        }
    }

    private fun event(context: Context, message: String) = AlarmSoundDiagnostics.event(context, message)
}
