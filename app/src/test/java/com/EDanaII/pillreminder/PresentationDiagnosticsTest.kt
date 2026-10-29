package com.EDanaII.pillreminder

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.PowerManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [AlarmPermissionsTest.FullScreenNotificationManager::class])
class PresentationDiagnosticsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val occurrence = AlarmOccurrence(7, 100, "private-token", true, "private-configuration")
    private val reminder = Reminder(id = 7, name = "Private medication", instructions = "Private instructions",
        startTimeMillis = 100, doseIntervalHours = 8, nagIntervalMinutes = 1)

    @Before fun enableDetailedDiagnostics() {
        DeveloperSettings.setEnabled(context, true)
        DeveloperSettings.setDetailed(context, true)
        AlarmPermissionsTest.FullScreenNotificationManager.allowed = true
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
    }

    @Test fun primaryNotificationRecordsGrantedPermissionAttachmentAndActualLoweredImportance() {
        manager.createNotificationChannel(NotificationChannel(AlarmNotifications.CHANNEL_ID,
            "User channel", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        AlarmNotifications.show(context, reminder, occurrence)
        val notification = manager.activeNotifications.single().notification
        assertNotNull(notification.fullScreenIntent)
        assertNull(manager.getNotificationChannel(AlarmNotifications.CHANNEL_ID).sound)
        val entry = AudioDiagnosticBuffer.export(context).lines().single { it.contains("PRESENTATION_NOTIFICATION") }
        assertTrue(entry.contains("surface=ALARM id=7 test=true fsiAllowed=true fsiAttached=true importance=2 postedImportance=2"))
        assertTrue(entry.contains("interactive=false keyguardLocked=true"))
        for (secret in listOf("Private medication", "Private instructions", "private-token", "private-configuration"))
            assertFalse(entry.contains(secret))
    }

    @Test fun deniedPermissionStillOmitsFullScreenIntentAndRecordsItWithoutChangingNotification() {
        AlarmPermissionsTest.FullScreenNotificationManager.allowed = false
        AlarmNotifications.show(context, reminder, occurrence)
        val notification = manager.activeNotifications.single().notification
        assertNull(notification.fullScreenIntent)
        assertNotNull(notification.contentIntent)
        assertNotNull(notification.actions.single().actionIntent)
        assertTrue(AudioDiagnosticBuffer.export(context).contains("fsiAllowed=false fsiAttached=false importance=4"))
    }

    @Test fun fallbackNotificationIsDistinguishedAndNeverGetsFullScreenIntent() {
        AlarmNotifications.ensureChannel(context)
        val request = AlarmPlaybackRequest(occurrence, 100, 0, null, false, true, true)
        AlarmNotificationAudio.show(context, request, 20_000, false)
        val notification = manager.activeNotifications.single().notification
        assertNull(notification.fullScreenIntent)
        assertEquals(20_000L, notification.timeoutAfter)
        assertTrue(AudioDiagnosticBuffer.export(context).contains(
            "surface=FALLBACK id=7 test=true fsiAllowed=true fsiAttached=false importance=4 postedImportance=4"))
    }

    @Test fun detailedLoggingOffDoesNotRecordPresentationButNotificationsStillPost() {
        DeveloperSettings.setDetailed(context, false)
        AlarmNotifications.show(context, reminder, occurrence)
        val controller = Robolectric.buildActivity(MedicationAlarmActivity::class.java).create()
        try { controller.get().finish() } finally { controller.destroy() }
        val exported = AudioDiagnosticBuffer.export(context)
        assertFalse(exported.contains("PRESENTATION_NOTIFICATION"))
        assertFalse(exported.contains("ALARM_ACTIVITY"))
        assertNotNull(manager.activeNotifications.single().notification.fullScreenIntent)
    }

    @Test fun invalidActivityIntentRecordsCreateResumeAndOneFinish() {
        val controller = Robolectric.buildActivity(MedicationAlarmActivity::class.java).create().start().resume()
        try {
            assertTrue(controller.get().isFinishing)
            controller.get().finish()
            val exported = AudioDiagnosticBuffer.export(context)
            assertTrue(exported.contains("ALARM_ACTIVITY lifecycle=CREATE"))
            assertTrue(exported.contains("ALARM_ACTIVITY lifecycle=RESUME"))
            assertTrue(exported.contains("ALARM_ACTIVITY lifecycle=FINISH finishReason=INVALID_OCCURRENCE"))
            assertEquals(1, exported.lines().count { it.contains("lifecycle=FINISH") })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun validIdentityIsSanitizedAndUnknownDeviceStateCannotThrow() {
        val intent = Intent(context, MedicationAlarmActivity::class.java)
            .putExtra(AlarmCoordinator.EXTRA_OCCURRENCE, AlarmCoordinator.encode(occurrence))
        val controller = Robolectric.buildActivity(MedicationAlarmActivity::class.java, intent).create()
        try {
            controller.get().finish()
            val unavailableState = object : ContextWrapper(context) {
                override fun getSystemService(name: String): Any? {
                    if (name == POWER_SERVICE || name == KEYGUARD_SERVICE) throw SecurityException("Private error")
                    return super.getSystemService(name)
                }
            }
            val notification = Notification.Builder(context, AlarmNotifications.CHANNEL_ID).build()
            PresentationDiagnostics.notification(unavailableState, notification, occurrence, "ALARM")
            val exported = AudioDiagnosticBuffer.export(context)
            assertTrue(exported.contains("ALARM_ACTIVITY lifecycle=CREATE id=7 test=true"))
            assertTrue(exported.contains("lifecycle=FINISH id=7 test=true finishReason=OTHER"))
            assertTrue(exported.contains("interactive=unknown keyguardLocked=unknown"))
            assertFalse(exported.contains("private-token"))
            assertFalse(exported.contains("private-configuration"))
            assertFalse(exported.contains("Private error"))
        } finally { controller.destroy() }
    }
}
