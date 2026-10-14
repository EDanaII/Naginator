package com.EDanaII.pillreminder

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmPermissionsTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val notifications: NotificationManager
        get() = application.getSystemService(NotificationManager::class.java)

    @Test
    @Config(sdk = [26])
    fun olderAndroidNeedsNoRuntimeNotificationOrSpecialAlarmAccess() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(AlarmPermissions.notificationsAllowed(application))
        assertTrue(AlarmPermissions.exactAlarmsAllowed(application))
        assertTrue(AlarmPermissions.fullScreenAllowed(application))
        assertNull(AlarmPermissions.exactAlarmSettings(application))
        assertNull(AlarmPermissions.fullScreenSettings(application))
        shadowOf(notifications).setNotificationsEnabled(false)
        assertFalse(AlarmPermissions.notificationsAllowed(application))
    }

    @Test
    fun notificationsReflectGrantRevocationAndAppWideBlocking() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(AlarmPermissions.notificationsAllowed(application))
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(AlarmPermissions.notificationsAllowed(application))
        shadowOf(notifications).setNotificationsEnabled(false)
        assertFalse(AlarmPermissions.notificationsAllowed(application))
        shadowOf(notifications).setNotificationsEnabled(true)
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(AlarmPermissions.notificationsAllowed(application))
    }

    @Test
    @Config(sdk = [31])
    fun exactAlarmAccessReflectsSettingsChanges() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(AlarmPermissions.exactAlarmsAllowed(application))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(AlarmPermissions.exactAlarmsAllowed(application))
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(AlarmPermissions.exactAlarmsAllowed(application))
        val intent = AlarmPermissions.exactAlarmSettings(application)!!
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.action)
        assertEquals("package:${application.packageName}", intent.dataString)
    }

    @Test
    fun beforeAndroid14FullScreenUsesManifestPermissionAndHasNoSpecialSettings() {
        shadowOf(application).grantPermissions(Manifest.permission.USE_FULL_SCREEN_INTENT)
        assertTrue(AlarmPermissions.fullScreenAllowed(application))
        shadowOf(application).denyPermissions(Manifest.permission.USE_FULL_SCREEN_INTENT)
        assertFalse(AlarmPermissions.fullScreenAllowed(application))
        assertNull(AlarmPermissions.fullScreenSettings(application))
    }

    @Test
    @Config(sdk = [34], shadows = [FullScreenNotificationManager::class])
    fun android14UsesFullScreenCapabilityRatherThanManifestGrantAlone() {
        shadowOf(application).grantPermissions(Manifest.permission.USE_FULL_SCREEN_INTENT)
        FullScreenNotificationManager.allowed = false
        assertFalse(AlarmPermissions.fullScreenAllowed(application))
        FullScreenNotificationManager.allowed = true
        assertTrue(AlarmPermissions.fullScreenAllowed(application))
        FullScreenNotificationManager.allowed = false
        assertFalse(AlarmPermissions.fullScreenAllowed(application))
        val intent = AlarmPermissions.fullScreenSettings(application)!!
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, intent.action)
        assertEquals("package:${application.packageName}", intent.dataString)
    }

    @Test
    fun notificationAndFallbackSettingsTargetThisApp() {
        val intent = AlarmPermissions.notificationSettings(application)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(application.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        val fallback = AlarmPermissions.appSettings(application)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, fallback.action)
        assertEquals("package:${application.packageName}", fallback.dataString)
    }

    @Test
    @Config(sdk = [34], shadows = [FullScreenNotificationManager::class])
    fun readingCapabilitiesDoesNotScheduleAlarmsOrPostNotifications() {
        FullScreenNotificationManager.allowed = false
        AlarmPermissions.read(application)
        assertTrue(shadowOf(application.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        assertEquals(0, notifications.activeNotifications.size)
    }

    // Robolectric 4.16 has no built-in shadow for this API.
    @Implements(NotificationManager::class, minSdk = 34)
    class FullScreenNotificationManager : ShadowNotificationManager() {
        @Implementation
        fun canUseFullScreenIntent(): Boolean = allowed

        companion object {
            var allowed = false
        }
    }
}
