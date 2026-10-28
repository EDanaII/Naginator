package com.EDanaII.pillreminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationAudioExperimentTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private fun fire() = shadowOf(alarms.scheduledAlarms.single().operation).savedIntent

    @Test fun schedulesOneExactWakeupAndPostsSystemSoundWithoutApplicationPlayback() {
        AlarmNotifications.ensureChannel(context)
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        NotificationAudioExperiment.schedule(context, 100_000L)
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(160_000L, alarm.triggerAtTime)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertTrue(alarm.allowWhileIdle)
        val intent = fire()
        MedicationAlarmReceiver().onReceive(context, intent)
        val notification = manager.activeNotifications.single().notification
        val channel = manager.getNotificationChannel(notification.channelId)
        assertEquals(NotificationAudioExperiment.CHANNEL_ID, channel.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), channel.sound)
        assertEquals(AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
        assertFalse(channel.shouldVibrate())
        assertEquals(30_000L, notification.timeoutAfter)
        assertEquals(0, notification.flags and Notification.FLAG_INSISTENT)
        assertNull(notification.fullScreenIntent)
        assertNull(manager.getNotificationChannel(AlarmNotifications.CHANNEL_ID).sound)
        MedicationAlarmReceiver().onReceive(context, intent) // Duplicate cannot post again.
        val log = AudioDiagnosticBuffer.export(context)
        assertEquals(1, log.lines().count { it.contains("DIAGNOSTIC_POST status=ACCEPTED") })
        assertTrue(log.contains("DIAGNOSTIC_SCHEDULE status=REGISTERED atMs=160000"))
        assertTrue(log.contains("importance=4 soundEnabled=true"))
        assertFalse(log.contains("FOCUS_REQUEST"))
        assertFalse(log.contains("SERVICE_REQUEST"))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun cancelPendingRejectsAlreadyDispatchedDelivery() {
        NotificationAudioExperiment.schedule(context)
        val intent = fire()
        NotificationAudioExperiment.cancel(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        MedicationAlarmReceiver().onReceive(context, intent)
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(AudioDiagnosticBuffer.export(context).contains("DIAGNOSTIC_CANCEL reason=CANCELED"))
    }

    @Test fun dismissActionCancelsNotificationAndDoesNotScheduleNags() {
        NotificationAudioExperiment.schedule(context)
        MedicationAlarmReceiver().onReceive(context, fire())
        val notification = manager.activeNotifications.single().notification
        MedicationAlarmReceiver().onReceive(context, shadowOf(notification.actions.single().actionIntent).savedIntent)
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(alarms.scheduledAlarms.isEmpty()) // No successor; dismissal cancels the original too.
        assertTrue(AudioDiagnosticBuffer.export(context).contains("DIAGNOSTIC_CANCEL reason=DISMISSED"))
    }

    @Test fun replacementInvalidatesOldDeliveryAndOldDismissal() {
        NotificationAudioExperiment.schedule(context)
        val old = android.content.Intent(fire())
        MedicationAlarmReceiver().onReceive(context, old)
        val oldAction = manager.activeNotifications.single().notification.actions.single().actionIntent
        val oldDismiss = android.content.Intent(shadowOf(oldAction).savedIntent)
        NotificationAudioExperiment.schedule(context)
        assertEquals(1, alarms.scheduledAlarms.size)
        MedicationAlarmReceiver().onReceive(context, old)
        assertTrue(manager.activeNotifications.isEmpty())
        MedicationAlarmReceiver().onReceive(context, fire())
        assertNotEquals(oldAction, manager.activeNotifications.single().notification.actions.single().actionIntent)
        MedicationAlarmReceiver().onReceive(context, oldDismiss)
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun blockedChannelIsRespectedAndOrdinaryChannelsAreNotCreated() {
        manager.createNotificationChannel(android.app.NotificationChannel(
            NotificationAudioExperiment.CHANNEL_ID, "Blocked diagnostic", NotificationManager.IMPORTANCE_NONE))
        NotificationAudioExperiment.schedule(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNull(manager.getNotificationChannel(AlarmNotifications.CHANNEL_ID))
        assertTrue(AudioDiagnosticBuffer.export(context).contains("DIAGNOSTIC_SCHEDULE status=FAILED"))
    }

    @Test fun userSilencedChannelIsNotOverridden() {
        manager.createNotificationChannel(android.app.NotificationChannel(
            NotificationAudioExperiment.CHANNEL_ID, "Silent diagnostic", NotificationManager.IMPORTANCE_HIGH)
            .apply { setSound(null, null) })
        NotificationAudioExperiment.schedule(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNull(manager.getNotificationChannel(NotificationAudioExperiment.CHANNEL_ID).sound)
    }
}
