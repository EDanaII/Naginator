package com.EDanaII.pillreminder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ActivityOptions
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.content.ContextCompat

object AlarmNotifications {
    const val CHANNEL_ID = "medication_alerts_v2"
    const val ALERT_DURATION_MS = 30_000L

    fun ensureChannel(context: Context) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val channel = NotificationChannel(CHANNEL_ID, "Medication alarms",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Scheduled medication alerts using the default alarm sound"
            // Playback is owned by the bounded shared controller, not the notification channel.
            setSound(null, attributes)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean = suppressionReason(context) == null

    /** Current notification access only; obsolete channels do not govern current alarms. */
    internal fun suppressionReason(context: Context): String? {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        return when {
            Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                "POST_NOTIFICATIONS permission denied"
            !manager.areNotificationsEnabled() -> "app-wide notifications disabled"
            manager.getNotificationChannel(CHANNEL_ID).importance == NotificationManager.IMPORTANCE_NONE ->
                "active notification channel $CHANNEL_ID blocked"
            else -> null
        }
    }

    fun show(context: Context, reminder: Reminder, occurrence: AlarmOccurrence) {
        val unlocked = DirectBootAlarmStore.isUnlocked(context)
        val title = if (unlocked) reminder.name else "Medication reminder"
        val instructions = if (unlocked) reminder.instructions else "Unlock your phone to view medication details."
        // PendingIntents carry identity only, never the legacy configuration containing medication text.
        val identityPayload = AlarmCoordinator.encode(occurrence.copy(configuration = ""))
        ensureChannel(context)
        val options = if (Build.VERSION.SDK_INT >= 35 && AlarmPermissions.fullScreenAllowed(context)) {
            ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(
                    if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle()
        } else null
        val contentIntent = PendingIntent.getActivity(context, 0,
            Intent(context, MedicationAlarmActivity::class.java)
                .setData(identity(occurrence, "open"))
                .putExtra(AlarmCoordinator.EXTRA_OCCURRENCE, identityPayload)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options)
        val dismissIntent = PendingIntent.getBroadcast(context, 0,
            Intent(context, MedicationAlarmReceiver::class.java)
                .setAction(AlarmCoordinator.ACTION_DISMISS)
                .setData(identity(occurrence, "dismiss"))
                .putExtra(AlarmCoordinator.EXTRA_OCCURRENCE, identityPayload),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle((if (occurrence.test) "Test: " else "") + title)
            .setContentText(instructions)
            .setStyle(Notification.BigTextStyle().bigText(instructions))
            .setWhen(occurrence.scheduledTime)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setTimeoutAfter(ALERT_DURATION_MS)
            .addAction(Notification.Action.Builder(null, "Dismiss", dismissIntent).build())
            .apply {
                if (AlarmPermissions.fullScreenAllowed(context)) setFullScreenIntent(contentIntent, true)
            }.build()
        context.getSystemService(NotificationManager::class.java)
            .notify(tag(occurrence.reminderId, occurrence.test, occurrence.token), 1, notification)
    }

    fun cancel(context: Context, id: Long, test: Boolean, token: String? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (token != null) manager.cancel(tag(id, test, token), 1)
        else manager.activeNotifications.filter {
            it.tag == tag(id, test) || it.tag?.startsWith(tag(id, test) + ":") == true
        }.forEach { manager.cancel(it.tag, it.id) }
    }

    internal fun tag(id: Long, test: Boolean, token: String? = null) =
        "medication:$id:$test" + (token?.let { ":$it" } ?: "")

    private fun identity(occurrence: AlarmOccurrence, action: String) =
        Uri.parse("pillreminder://$action/${occurrence.reminderId}/${occurrence.test}/${occurrence.token}")
}
