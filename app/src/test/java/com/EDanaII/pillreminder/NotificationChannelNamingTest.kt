package com.EDanaII.pillreminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class NotificationChannelNamingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun request(at: Long = 100) = AlarmPlaybackRequest(
        AlarmOccurrence(1, 100, "naming-test", true, "revision"), at, 0, null, true, true, true)

    @Before fun setup() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun behavior(channel: NotificationChannel): List<Any?> = listOf(channel.id,
        channel.importance, channel.sound, channel.audioAttributes, channel.shouldVibrate(),
        channel.vibrationPattern?.toList(), channel.lockscreenVisibility, channel.canBypassDnd(), channel.canShowBadge())

    @Test fun presentationAndDeveloperLabelsKeepTheirIdsAndDefaults() {
        AlarmNotifications.ensureChannel(context)
        NotificationAudioExperiment.schedule(context)
        val presentation = manager.getNotificationChannel("medication_alerts_v2")
        assertEquals("Medication alarms (presentation)", presentation.name)
        assertTrue(presentation.description.contains("Dismiss"))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, presentation.importance)
        assertNull(presentation.sound)
        assertFalse(presentation.shouldVibrate())
        val diagnostic = manager.getNotificationChannel("diagnostic_notification_alarm_v1")
        assertEquals("Developer: system alarm test", diagnostic.name)
        assertTrue(diagnostic.description.contains("one-shot"))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, diagnostic.importance)
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), diagnostic.sound)
        assertFalse(diagnostic.shouldVibrate())
        assertEquals(setOf(presentation.id, diagnostic.id), manager.notificationChannels.map { it.id }.toSet())
        NotificationAudioExperiment.cancel(context)
    }

    @Test fun defaultFallbackVibrationVariantIsNotMislabeledAsSelectedSound() {
        AlarmNotificationAudio.show(context, request(), 30_000, false)
        AlarmNotificationAudio.show(context, request(200), 30_000, true)
        val sound = manager.getNotificationChannel("medication_audio_fallback_v1")
        val vibration = manager.getNotificationChannel("medication_audio_fallback_v1_vibration")
        assertEquals("Alarm audio: system", sound.name)
        assertEquals("Alarm audio + vibration: system", vibration.name)
        assertFalse(vibration.name.toString().contains("selected"))
        assertNotEquals(sound.description, vibration.description)
        assertFalse(sound.shouldVibrate())
        assertTrue(vibration.shouldVibrate())
        for (channel in listOf(sound, vibration)) {
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
            assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), channel.sound)
            assertEquals(AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
        }
        assertEquals(setOf("medication_alerts_v2", sound.id, vibration.id),
            manager.notificationChannels.map { it.id }.toSet())
    }

    @Test fun existingPresentationAndFallbackBehaviorSettingsSurviveMetadataUpdates() {
        val seed = listOf("medication_alerts_v2", "medication_audio_fallback_v1").map { id ->
            NotificationChannel(id, "Old name", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Old description"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
                vibrationPattern = longArrayOf(0, 77, 23, 77)
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                setShowBadge(false)
            }.also(manager::createNotificationChannel)
        }
        val before = seed.associate { it.id to behavior(manager.getNotificationChannel(it.id)) }
        AlarmNotifications.ensureChannel(context)
        AlarmNotificationAudio.show(context, request(), 30_000, false)
        assertEquals("Medication alarms (presentation)", manager.getNotificationChannel(seed[0].id).name)
        assertEquals("Alarm audio: system", manager.getNotificationChannel(seed[1].id).name)
        seed.forEach { assertEquals(before.getValue(it.id), behavior(manager.getNotificationChannel(it.id))) }
        assertEquals(before.keys, manager.notificationChannels.map { it.id }.toSet())
    }

    @Test fun playbackServiceLabelKeepsItsSilentLowChannel() {
        val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
        try {
            controller.get().onStartCommand(null, 0, 1)
            val channel = manager.getNotificationChannel("alarm_playback")
            assertEquals("Alarm playback service", channel.name)
            assertTrue(channel.description.contains("silent"))
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertNull(channel.sound)
            assertFalse(channel.shouldVibrate())
        } finally { controller.destroy() }
    }

    @Test fun silencedDeveloperChannelAndObsoleteLegacyChannelAreNotResetOrDeleted() {
        val diagnostic = NotificationChannel("diagnostic_notification_alarm_v1", "Old diagnostic",
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) }
        val legacy = NotificationChannel("medication_alarms", "Medication alarms", NotificationManager.IMPORTANCE_NONE)
        manager.createNotificationChannel(diagnostic)
        manager.createNotificationChannel(legacy)
        val before = behavior(manager.getNotificationChannel(diagnostic.id))
        NotificationAudioExperiment.schedule(context) // Still refuses the user's silent channel.
        assertEquals("Developer: system alarm test", manager.getNotificationChannel(diagnostic.id).name)
        assertEquals(before, behavior(manager.getNotificationChannel(diagnostic.id)))
        assertEquals("Medication alarms", manager.getNotificationChannel(legacy.id).name)
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(legacy.id).importance)
        assertEquals(setOf(diagnostic.id, legacy.id), manager.notificationChannels.map { it.id }.toSet())
        assertTrue(shadowOf(context.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }
}
