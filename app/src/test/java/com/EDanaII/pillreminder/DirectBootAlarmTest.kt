package com.EDanaII.pillreminder

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DirectBootAlarmTest {
    private lateinit var context: Context
    private lateinit var database: ReminderDatabase
    private lateinit var coordinator: AlarmCoordinator
    private val users get() = shadowOf(context.getSystemService(UserManager::class.java))
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val manager get() = context.getSystemService(AlarmManager::class.java)
    private val alarms get() = shadowOf(manager)
    private val store get() = DirectBootAlarmStore(context)

    @Before fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        // Make any accidental credential preference access fail, not silently pass in Robolectric.
        context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(DirectBootAlarmStore.isUnlocked(this)) { "Unexpected credential storage access: $name" }
                return super.getSharedPreferences(name, mode)
            }
        }
        ReflectionHelpers.setStaticField(ReminderDatabase::class.java, "instance", null)
        context.deleteDatabase("pill-reminders.db")
        store.preferences.edit().clear().commit()
        for (name in listOf("alarm_occurrences", "alarm_configurations")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        if (Build.VERSION.SDK_INT >= 33) shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        database = ReminderDatabase.getInstance(context)
        coordinator = AlarmCoordinator(context)
    }

    @After fun tearDown() {
        users.setUserUnlocked(true)
        database.close()
        ReflectionHelpers.setStaticField(ReminderDatabase::class.java, "instance", null)
    }

    private fun reminder() = Reminder(name = "Private medication name", instructions = "Secret dose instructions",
        startTimeMillis = System.currentTimeMillis() + 300_000, doseIntervalHours = 8, nagIntervalMinutes = 23)

    private suspend fun save(value: Reminder = reminder()): Reminder {
        coordinator.save(value)
        return database.reminderDao().getAll().maxBy { it.id }
    }

    private fun lockAndLoseOsAlarms() {
        alarms.scheduledAlarms.forEach { manager.cancel(checkNotNull(it.operation)) }
        // The default coordinator used by manifest receivers cannot read this database while locked.
        database.close()
        users.setUserUnlocked(false)
        coordinator = AlarmCoordinator(context)
    }

    private fun unlock() {
        users.setUserUnlocked(true)
        ReflectionHelpers.setStaticField(ReminderDatabase::class.java, "instance", null)
        database = ReminderDatabase.getInstance(context)
        coordinator = AlarmCoordinator(context)
    }

    private fun broadcast(action: String) {
        val complete = CountDownLatch(1)
        context.sendOrderedBroadcast(Intent(action).setPackage(context.packageName), null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { complete.countDown() }
            }, Handler(Looper.getMainLooper()), 0, null, null)
        awaitCondition { complete.count == 0L }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val pause = CountDownLatch(1)
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            pause.await(10, TimeUnit.MILLISECONDS)
        }
        fail("Asynchronous alarm operation did not finish")
    }

    private suspend fun overdue(): Pair<Reminder, AlarmOccurrence> {
        val reminder = save(reminder().copy(startTimeMillis = System.currentTimeMillis() - 60_000))
        val occurrence = coordinator.scheduled(reminder.id, false)!!.copy(scheduledTime = reminder.startTimeMillis)
        check(store.preferences.edit().putString("scheduled:${reminder.id}:false",
            AlarmCoordinator.encode(occurrence)).commit())
        return reminder to occurrence
    }

    @Test
    @Config(sdk = [26, 31, 34])
    fun lockedBootRestoresOverdueAlarmAndReceiverDeliversWithoutRoom() = runBlocking {
        val (reminder, original) = overdue()
        lockAndLoseOsAlarms()

        broadcast(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        val restored = alarms.scheduledAlarms.single()
        assertEquals(original.scheduledTime, restored.triggerAtTime)
        assertEquals(original, coordinator.scheduled(reminder.id, false))
        alarms.fireAlarm(restored)
        awaitCondition { notifications.activeNotifications.size == 1 }

        val active = coordinator.active(reminder.id, false, original.token)!!
        assertEquals(original, active.occurrence)
        assertEquals("Medication reminder", active.reminder.name)
        val notification = notifications.activeNotifications.single().notification
        assertEquals("Medication reminder", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Unlock your phone to view medication details.", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(AlarmNotifications.ALERT_DURATION_MS, notification.timeoutAfter)
        assertNotNull(notification.contentIntent)
        assertNotNull(notification.actions.single().actionIntent)
        val next = coordinator.scheduled(reminder.id, false)!!
        assertEquals(reminder.startTimeMillis + 8 * 3_600_000L, next.scheduledTime)
        assertFalse(coordinator.deliver(original))
        assertPrivate(reminder)
    }

    @Test fun lockedNotificationDismissalSurvivesUnlockAndDoesNotShiftNextDose() = runBlocking {
        val (reminder, original) = overdue()
        lockAndLoseOsAlarms()
        coordinator.synchronize()
        alarms.fireAlarm(alarms.scheduledAlarms.single())
        awaitCondition { notifications.activeNotifications.size == 1 }
        val next = coordinator.scheduled(reminder.id, false)!!
        notifications.activeNotifications.single().notification.actions.single().actionIntent.send()
        awaitCondition { notifications.activeNotifications.isEmpty() }
        assertNull(coordinator.active(reminder.id, false, original.token))
        assertEquals(next, coordinator.scheduled(reminder.id, false))

        unlock()
        broadcast(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(reminder, database.reminderDao().findById(reminder.id))
        assertTrue(coordinator.uiState().activeAlarms.isEmpty())
        assertEquals(next, coordinator.scheduled(reminder.id, false))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun duplicateBootEventsAndUnlockKeepLockedDeliveryInsteadOfReplayingIt() = runBlocking {
        val (reminder, original) = overdue()
        lockAndLoseOsAlarms()
        repeat(2) { broadcast(Intent.ACTION_LOCKED_BOOT_COMPLETED) }
        assertEquals(1, alarms.scheduledAlarms.size)
        alarms.fireAlarm(alarms.scheduledAlarms.single())
        awaitCondition { notifications.activeNotifications.size == 1 }
        val next = coordinator.scheduled(reminder.id, false)!!
        broadcast(Intent.ACTION_BOOT_COMPLETED) // Also safe if received while storage is still locked.
        assertEquals(next, coordinator.scheduled(reminder.id, false))

        unlock()
        repeat(2) { broadcast(Intent.ACTION_BOOT_COMPLETED) }
        assertEquals(original, coordinator.uiState().activeAlarms.single().occurrence)
        assertEquals(reminder, coordinator.uiState().activeAlarms.single().reminder)
        assertEquals(next, coordinator.scheduled(reminder.id, false))
        assertFalse(coordinator.deliver(original))
        assertEquals(2, alarms.scheduledAlarms.size) // Next dose and the still-unacknowledged nag.
        assertEquals(1, notifications.activeNotifications.size)
    }

    @Test fun migrationPreservesLegacyPendingAndActiveTokensAndRemovesPrivateRuntimePayloads() = runBlocking {
        val value = reminder()
        val id = database.reminderDao().insert(value)
        val reminder = value.copy(id = id)
        val configuration = JSONArray(listOf(id, reminder.name, reminder.instructions,
            reminder.startTimeMillis, reminder.doseIntervalHours, reminder.nagIntervalMinutes, true)).toString()
        val pending = AlarmOccurrence(id, reminder.startTimeMillis, "original-dose-token", false, configuration)
        val active = pending.copy(scheduledTime = reminder.startTimeMillis - 8 * 3_600_000L, token = "original-active-token")
        val legacy = context.getSharedPreferences("alarm_occurrences", Context.MODE_PRIVATE)
        legacy.edit().putString("scheduled:$id:false", AlarmCoordinator.encode(pending))
            .putString("active:$id:false", AlarmCoordinator.encode(active)).commit()

        broadcast(Intent.ACTION_MY_PACKAGE_REPLACED)

        val migrated = coordinator.scheduled(id, false)!!
        assertEquals(pending.copy(configuration = migrated.configuration), migrated)
        assertEquals(active.token, coordinator.active(id, false, active.token)!!.occurrence.token)
        assertTrue(legacy.all.isEmpty())
        assertPrivate(reminder)
        // Reintroducing stale legacy data must not restore a dismissed occurrence.
        coordinator.dismiss(id, false, active.token)
        legacy.edit().putString("active:$id:false", AlarmCoordinator.encode(active)).commit()
        coordinator.synchronize()
        assertNull(coordinator.active(id, false, active.token))
        assertEquals(migrated, coordinator.scheduled(id, false))
        // A PendingIntent created by the previous version may still carry its legacy payload.
        assertTrue(coordinator.deliver(pending, pending.scheduledTime))
        assertEquals(pending.token, coordinator.active(id, false, pending.token)!!.occurrence.token)
        assertPrivate(reminder)
    }

    @Test fun packageReplacementBackfillsExistingRoomRowsBeforeNextLockedReboot() = runBlocking {
        val value = reminder()
        val id = database.reminderDao().insert(value)
        broadcast(Intent.ACTION_MY_PACKAGE_REPLACED)
        val pending = coordinator.scheduled(id, false)!!
        assertEquals(value.startTimeMillis, pending.scheduledTime)
        lockAndLoseOsAlarms()
        broadcast(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        assertEquals(pending, coordinator.scheduled(id, false))
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test fun interruptedDisableInvalidatesOldTokenBeforeRoomWriteAndRepairsAfterUnlock() = runBlocking {
        val reminder = save()
        val original = coordinator.scheduled(reminder.id, false)!!
        val failing = object : ReminderDao by database.reminderDao() {
            override suspend fun setEnabled(id: Long, enabled: Boolean): Int = error("Interrupted Room write")
        }
        try {
            AlarmCoordinator(context, failing).setEnabled(reminder.id, false)
            fail("Expected the simulated failure")
        } catch (expected: IllegalStateException) {
            assertEquals("Interrupted Room write", expected.message)
        }
        assertTrue(store.preferences.getBoolean("repair:${reminder.id}", false))
        assertNull(store.definition(reminder.id))
        lockAndLoseOsAlarms()
        coordinator.synchronize()
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertFalse(coordinator.deliver(original))
        unlock()
        coordinator.synchronize()
        assertFalse(store.preferences.getBoolean("repair:${reminder.id}", false))
        assertNotEquals(original.token, coordinator.scheduled(reminder.id, false)!!.token)
        assertEquals(reminder, database.reminderDao().findById(reminder.id))
    }

    @Test fun editsDisableAndDeleteRejectOldTokensAcrossLockedReboot() = runBlocking {
        val edited = save()
        val disabled = save()
        val deleted = save()
        val stale = listOf(edited, disabled, deleted).map { coordinator.scheduled(it.id, false)!! }
        coordinator.save(edited.copy(instructions = "Updated confidential instructions"))
        val replacement = coordinator.scheduled(edited.id, false)!!
        coordinator.setEnabled(disabled.id, false)
        coordinator.delete(deleted.id)
        lockAndLoseOsAlarms()
        coordinator.synchronize()
        assertEquals(replacement, coordinator.scheduled(edited.id, false))
        assertEquals(1, alarms.scheduledAlarms.size)
        stale.forEach { assertFalse(coordinator.deliver(it, it.scheduledTime)) }
        assertNull(store.definition(disabled.id))
        assertNull(store.definition(deleted.id))
    }

    @Test fun interruptedWriteAfterRoomCommitCannotRestoreDisabledAlarm() = runBlocking {
        val reminder = save()
        val original = coordinator.scheduled(reminder.id, false)!!
        val dao = database.reminderDao()
        val failing = object : ReminderDao by dao {
            override suspend fun setEnabled(id: Long, enabled: Boolean): Int {
                dao.setEnabled(id, enabled)
                error("Reboot after Room commit")
            }
        }
        try {
            AlarmCoordinator(context, failing).setEnabled(reminder.id, false)
            fail("Expected simulated interruption")
        } catch (expected: IllegalStateException) {
            assertEquals("Reboot after Room commit", expected.message)
        }
        lockAndLoseOsAlarms()
        broadcast(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertFalse(coordinator.deliver(original))
        unlock()
        broadcast(Intent.ACTION_BOOT_COMPLETED)
        assertFalse(database.reminderDao().findById(reminder.id)!!.enabled)
        assertNull(store.definition(reminder.id))
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun notificationIntentsNeverContainConfigurationOrMedicationText() = runBlocking {
        val (reminder, occurrence) = overdue()
        assertTrue(coordinator.deliver(occurrence))
        val notification = notifications.activeNotifications.single().notification
        val intents = listOf(notification.contentIntent, notification.fullScreenIntent,
            notification.actions.single().actionIntent).filterNotNull()
        intents.forEach { pending ->
            val intent = shadowOf(pending).savedIntent
            val payload = intent.getStringExtra(AlarmCoordinator.EXTRA_OCCURRENCE)!!
            assertEquals("", AlarmCoordinator.decode(payload)!!.configuration)
            assertFalse(payload.contains(reminder.name))
            assertFalse(payload.contains(reminder.instructions))
        }
        assertPrivate(reminder)
    }

    @Test
    @Config(sdk = [31], shadows = [RegistrationRace::class])
    fun registrationPermissionRaceRetainsOriginalIdentityForLockedRetry() = runBlocking {
        RegistrationRace.deny = false
        val reminder = save()
        val pending = coordinator.scheduled(reminder.id, false)!!
        lockAndLoseOsAlarms()
        try {
            RegistrationRace.deny = true
            assertTrue(coordinator.synchronize().contains("denied"))
            assertEquals(pending, coordinator.scheduled(reminder.id, false))
            assertTrue(alarms.scheduledAlarms.isEmpty())
            RegistrationRace.deny = false
            coordinator.synchronize()
            assertEquals(pending, coordinator.scheduled(reminder.id, false))
            assertEquals(pending.scheduledTime, alarms.scheduledAlarms.single().triggerAtTime)
        } finally {
            RegistrationRace.deny = false
        }
    }

    @Implements(AlarmManager::class)
    class RegistrationRace : ShadowAlarmManager() {
        @Implementation
        override fun setExactAndAllowWhileIdle(type: Int, triggerAtMs: Long, operation: PendingIntent) {
            if (deny) throw SecurityException("Exact alarm access changed during registration")
            super.setExactAndAllowWhileIdle(type, triggerAtMs, operation)
        }

        companion object { var deny = false }
    }

    @Test fun manifestMarksAllAlarmEntryPointsAwareAndBackupsExcludeRuntime() {
        for (action in listOf(Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            val receivers = context.packageManager.queryBroadcastReceivers(
                Intent(action).setPackage(context.packageName), PackageManager.MATCH_DIRECT_BOOT_AWARE)
            assertTrue(receivers.any { it.activityInfo.name == BootCompletedReceiver::class.java.name })
        }
        val activity = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MedicationAlarmActivity::class.java), 0)
        assertTrue(activity.directBootAware)
        val receiver = context.packageManager.getReceiverInfo(
            android.content.ComponentName(context, MedicationAlarmReceiver::class.java), 0)
        assertTrue(receiver.directBootAware)
        for (resource in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            val parser = context.resources.getXml(resource)
            var exclusions = 0
            while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "exclude" &&
                    parser.getAttributeValue(null, "domain") == "device_sharedpref" &&
                    parser.getAttributeValue(null, "path") == "direct_boot_alarms.xml") exclusions++
            }
            parser.close()
            assertEquals(if (resource == R.xml.backup_rules) 1 else 2, exclusions)
        }
    }

    private fun assertPrivate(reminder: Reminder) {
        val serialized = store.preferences.all.values.joinToString()
        assertFalse(serialized.contains(reminder.name))
        assertFalse(serialized.contains(reminder.instructions))
        assertFalse(serialized.contains("Updated confidential instructions"))
    }
}
