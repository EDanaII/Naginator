package com.EDanaII.pillreminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.SystemClock
import java.security.MessageDigest

/** System-owned, non-looping audio. Separate from silent presentation refreshes. */
internal object AlarmNotificationAudio {
    const val DEFAULT_CHANNEL = "medication_audio_fallback_v1"
    const val EXTRA_ALERT_AT = "fallback_alert_at"
    private fun prefix(id: Long, test: Boolean) = "fallback:$id:$test:"
    private fun tag(request: AlarmPlaybackRequest) = prefix(request.occurrence.reminderId,
        request.occurrence.test) + request.token + ":${request.alertAt}"

    private fun sound(context: Context, request: AlarmPlaybackRequest): Uri {
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val selected = request.soundUri?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return default
        if (selected == default) return default
        if (request.unlocked && selected.scheme == "content") {
            try {
                context.contentResolver.openAssetFileDescriptor(selected, "r")?.use { return selected }
            } catch (_: Exception) { /* Do not log private URIs or provider messages. */ }
        }
        AlarmSoundDiagnostics.event(context, "FALLBACK_SOUND source=SYSTEM_DEFAULT atMs=${request.alertAt}")
        return default
    }

    fun show(context: Context, request: AlarmPlaybackRequest, remaining: Long, serviceUnavailable: Boolean) {
        val expires = SystemClock.elapsedRealtime() + remaining
        try {
            if (!AlarmNotifications.canNotify(context)) {
                AlarmSoundDiagnostics.event(context, "FALLBACK_POST status=FAILED atMs=${request.alertAt}")
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            val uri = sound(context, request)
            val soundChannel = if (uri == RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)) DEFAULT_CHANNEL else DEFAULT_CHANNEL + "_" +
                MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
                    .joinToString("") { "%02x".format(it) }.take(24)
            val systemVibration = serviceUnavailable && request.vibration
            val channelId = soundChannel + if (systemVibration) "_vibration" else ""
            manager.createNotificationChannel(NotificationChannel(channelId,
                if (channelId == DEFAULT_CHANNEL) "Medication audio fallback" else "Medication audio fallback: selected sound",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alarm audio when application playback is unavailable"
                setSound(uri, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(systemVibration) // Never duplicate live service vibration.
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
            val payload = AlarmCoordinator.encode(request.occurrence.copy(configuration = ""))
            val dismiss = PendingIntent.getBroadcast(context, 0,
                Intent(context, MedicationAlarmReceiver::class.java).setAction(AlarmCoordinator.ACTION_DISMISS)
                    .setData(Uri.parse("pillreminder://fallback-dismiss/${request.token}/${request.alertAt}"))
                    .putExtra(EXTRA_ALERT_AT, request.alertAt)
                    .putExtra(AlarmCoordinator.EXTRA_OCCURRENCE, payload),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(context, 0,
                Intent(context, MedicationAlarmActivity::class.java)
                    .setData(Uri.parse("pillreminder://fallback-open/${request.token}/${request.alertAt}"))
                    .putExtra(AlarmCoordinator.EXTRA_OCCURRENCE, payload)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val left = expires - SystemClock.elapsedRealtime()
            if (left <= 0) return
            manager.notify(tag(request), 2, Notification.Builder(context, channelId)
                .withNaggyIcons(context)
                .setContentTitle("Medication alarm audio")
                .setContentText("Open the reminder or dismiss this alarm.")
                .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .setOnlyAlertOnce(true).setTimeoutAfter(left.coerceIn(1, AlertEscalation.BURST_MS))
                .addAction(Notification.Action.Builder(null, "Dismiss", dismiss).build()).build())
            val channel = manager.getNotificationChannel(channelId)
            AlarmSoundDiagnostics.event(context, "FALLBACK_POST status=ACCEPTED atMs=${request.alertAt} " +
                "importance=${channel.importance} soundEnabled=${channel.sound != null}")
        } catch (_: RuntimeException) {
            AlarmSoundDiagnostics.event(context, "FALLBACK_POST status=FAILED atMs=${request.alertAt}")
        }
    }

    fun cancel(context: Context, request: AlarmPlaybackRequest, reason: String) {
        context.getSystemService(NotificationManager::class.java).cancel(tag(request), 2)
        AlarmSoundDiagnostics.event(context, "FALLBACK_CANCEL reason=$reason atMs=${request.alertAt}")
    }

    fun cancelOccurrence(context: Context, id: Long, test: Boolean, token: String?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val prefix = prefix(id, test) + (token?.let { "$it:" } ?: "")
        manager.activeNotifications.filter { it.tag?.startsWith(prefix) == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }
}
