package com.EDanaII.pillreminder

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.room.Room
import kotlinx.coroutines.runBlocking
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
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], qualifiers = "mdpi")
class NaggyNotificationIconsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    @Before fun setup() {
        AlarmAudioOwnership.resetForTests()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(context.getSystemService(android.os.UserManager::class.java)).setUserUnlocked(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun assertNaggy(notification: Notification) {
        assertEquals(R.drawable.ic_notification_naggy, notification.smallIcon.resId)
        assertNotNull(notification.getLargeIcon())
        assertEquals(R.mipmap.ic_launcher_foreground, notification.getLargeIcon().resId)
    }

    @Test fun monochromeMaskAndColorAssetResolveInLightAndDarkModes() {
        val namespace = "http://schemas.android.com/apk/res/android"
        context.resources.getXml(R.drawable.ic_notification_naggy).use { xml ->
            var paths = 0
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "path") {
                    assertEquals("#ffffffff", xml.getAttributeValue(namespace, "fillColor").lowercase())
                    paths++
                }
                xml.next()
            }
            assertTrue(paths > 0)
        }
        for (mode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            })
            val glyph = themed.getDrawable(R.drawable.ic_notification_naggy)!!
            assertEquals(24, glyph.intrinsicWidth)
            assertEquals(24, glyph.intrinsicHeight)
            val color = BitmapFactory.decodeResource(themed.resources, R.mipmap.ic_launcher_foreground)!!
            assertEquals(108, color.width)
            assertEquals(108, color.height)
            // Inspect PNG bytes too: Robolectric's legacy Bitmap shadow does not retain alpha accurately.
            val png = themed.resources.openRawResource(R.mipmap.ic_launcher_foreground).use {
                javax.imageio.ImageIO.read(it)
            }
            assertTrue(png.colorModel.hasAlpha())
            assertEquals(0, png.getRGB(0, 0) ushr 24)
            assertNaggy(Notification.Builder(themed, "test").withNaggyIcons(themed).build())
        }
    }

    @Test fun initialReminderNagAndAudioFallbackShareIdentityWithoutChangingChannels() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, ReminderDatabase::class.java).build()
        try {
            val coordinator = AlarmCoordinator(context, database.reminderDao()) { _, _, _ -> true }
            val now = System.currentTimeMillis()
            coordinator.save(Reminder(name = "Dose", instructions = "Details", startTimeMillis = now + 60_000,
                doseIntervalHours = 8, nagIntervalMinutes = 1))
            val id = database.reminderDao().getAll().single().id
            val occurrence = coordinator.scheduled(id, false)!!
            assertTrue(coordinator.deliver(occurrence, occurrence.scheduledTime))
            assertNaggy(notifications.activeNotifications.single().notification)
            assertEquals(AlarmNotifications.CHANNEL_ID, notifications.activeNotifications.single().notification.channelId)
            val state = coordinator.nagState(occurrence.token)!!
            assertTrue(coordinator.nag(occurrence, state.nextNagTime, state.nextNagTime))
            assertNaggy(notifications.activeNotifications.single().notification)
            assertNull(notifications.getNotificationChannel(AlarmNotifications.CHANNEL_ID).sound)
            val request = coordinator.playback(occurrence.token, state.nextNagTime)!!
            AlarmAudioOwnership.fallback(context, request)
            val fallback = notifications.activeNotifications.single { it.tag.startsWith("fallback:") }.notification
            assertNaggy(fallback)
            assertEquals(AlarmNotificationAudio.DEFAULT_CHANNEL, fallback.channelId)
            assertNotNull(notifications.getNotificationChannel(fallback.channelId).sound)
            coordinator.dismiss(id, false, occurrence.token)
        } finally { database.close() }
    }

    @Test fun playbackServiceUsesSameIdentityAndExistingSilentChannel() {
        val controller = Robolectric.buildService(AlarmPlaybackService::class.java).create()
        val service = controller.get()
        service.preparePlayback = { _, _ -> null }
        try {
            service.onStartCommand(Intent(context, AlarmPlaybackService::class.java)
                .putExtra("token", "icon-test").putExtra("alert_at", 100L), 0, 1)
            val foreground = shadowOf(service).lastForegroundNotification
            assertNaggy(foreground)
            assertEquals("alarm_playback", foreground.channelId)
            assertNull(notifications.getNotificationChannel("alarm_playback").sound)
        } finally { controller.destroy() }
    }

    @Test fun diagnosticExperimentUsesSameIdentityAndRetainsItsChannel() {
        NotificationAudioExperiment.schedule(context)
        val alarm = shadowOf(context.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.single()
        MedicationAlarmReceiver().onReceive(context, shadowOf(alarm.operation).savedIntent)
        val diagnostic = notifications.activeNotifications.single().notification
        assertNaggy(diagnostic)
        assertEquals(NotificationAudioExperiment.CHANNEL_ID, diagnostic.channelId)
        NotificationAudioExperiment.cancel(context)
    }
}
