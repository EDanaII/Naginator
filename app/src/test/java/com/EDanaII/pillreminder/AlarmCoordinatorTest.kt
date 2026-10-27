package com.EDanaII.pillreminder

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AlarmCoordinatorTest {
    private lateinit var context: Context
    private lateinit var database: ReminderDatabase
    private lateinit var coordinator: AlarmCoordinator
    private val now get() = System.currentTimeMillis()
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(android.os.UserManager::class.java)).setUserUnlocked(true)
        DirectBootAlarmStore(context).preferences.edit().clear().commit()
        context.getSharedPreferences("alarm_configurations", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("alarm_occurrences", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, ReminderDatabase::class.java).build()
        coordinator = AlarmCoordinator(context, database.reminderDao())
    }

    @After fun tearDown() {
        database.close()
    }

    private fun reminder() = Reminder(name = "Morning pills", instructions = "Pill A — 2 tablets",
        startTimeMillis = now + 120_000, doseIntervalHours = 8, nagIntervalMinutes = 15)

    private suspend fun saved(): Reminder {
        coordinator.save(reminder())
        return database.reminderDao().getAll().single()
    }

    @Test fun schedulesExactWakeupAtFutureStart() = runBlocking {
        val reminder = saved()
        val occurrence = coordinator.scheduled(reminder.id, false)!!
        assertEquals(reminder.startTimeMillis, occurrence.scheduledTime)
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(reminder.startTimeMillis, alarm.triggerAtTime)
        assertTrue(alarm.allowWhileIdle)
    }

    @Test fun deliverySchedulesNextAnchoredDoseAndDismissLeavesItUntouched() = runBlocking {
        val reminder = saved()
        val occurrence = coordinator.scheduled(reminder.id, false)!!
        assertTrue(coordinator.deliver(occurrence, occurrence.scheduledTime))
        val next = coordinator.scheduled(reminder.id, false)!!
        assertEquals(reminder.startTimeMillis + 8 * 3_600_000L, next.scheduledTime)
        assertEquals(1, notifications.activeNotifications.size)
        assertNotNull(coordinator.active(reminder.id, false, occurrence.token))
        coordinator.dismiss(reminder.id, false, occurrence.token)
        assertEquals(0, notifications.activeNotifications.size)
        assertNull(coordinator.active(reminder.id, false, occurrence.token))
        assertEquals(next, coordinator.scheduled(reminder.id, false))
        assertFalse(coordinator.deliver(occurrence, occurrence.scheduledTime))
    }

    @Test fun editingReplacesPendingAlarmAndRejectsOldBroadcast() = runBlocking {
        val reminder = saved()
        val old = coordinator.scheduled(reminder.id, false)!!
        val edited = reminder.copy(startTimeMillis = reminder.startTimeMillis + 300_000,
            instructions = "Updated instructions")
        coordinator.save(edited)
        assertEquals(edited.startTimeMillis, coordinator.scheduled(reminder.id, false)!!.scheduledTime)
        assertFalse(coordinator.deliver(old, old.scheduledTime))
        assertEquals(0, notifications.activeNotifications.size)
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test fun disabledAndDeletedRemindersCannotDeliverStaleAlarms() = runBlocking {
        val reminder = saved()
        coordinator.testInOneMinute(reminder.id, now)
        val old = coordinator.scheduled(reminder.id, false)!!
        val test = coordinator.scheduled(reminder.id, true)!!
        coordinator.setEnabled(reminder.id, false)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertFalse(coordinator.deliver(old, old.scheduledTime))
        assertFalse(coordinator.deliver(test, test.scheduledTime))
        coordinator.setEnabled(reminder.id, true)
        val enabled = coordinator.scheduled(reminder.id, false)!!
        coordinator.delete(reminder.id)
        assertFalse(coordinator.deliver(enabled, enabled.scheduledTime))
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun testAlarmDoesNotReplaceOrAdvanceNormalSchedule() = runBlocking {
        val reminder = saved()
        val normal = coordinator.scheduled(reminder.id, false)!!
        coordinator.testInOneMinute(reminder.id, now)
        val test = coordinator.scheduled(reminder.id, true)!!
        assertEquals(normal, coordinator.scheduled(reminder.id, false))
        assertEquals(2, alarms.scheduledAlarms.size)
        assertTrue(coordinator.deliver(test, test.scheduledTime))
        coordinator.dismiss(reminder.id, true, test.token)
        assertEquals(normal, coordinator.scheduled(reminder.id, false))
        assertEquals(reminder, database.reminderDao().findById(reminder.id))
    }

    @Test fun remindersRetainIndependentSchedules() = runBlocking {
        val first = saved()
        coordinator.save(reminder().copy(name = "Evening pills", startTimeMillis = now + 240_000))
        val second = database.reminderDao().getAll().first { it.id != first.id }
        val secondOccurrence = coordinator.scheduled(second.id, false)
        coordinator.setEnabled(first.id, false)
        assertEquals(secondOccurrence, coordinator.scheduled(second.id, false))
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test fun notificationContainsInstructionsDefaultAlarmSoundAndBoundedTimeout() = runBlocking {
        val reminder = saved()
        val occurrence = coordinator.scheduled(reminder.id, false)!!
        coordinator.deliver(occurrence, occurrence.scheduledTime)
        val notification = notifications.activeNotifications.single().notification
        assertEquals(reminder.instructions, notification.extras.getString(Notification.EXTRA_BIG_TEXT))
        assertEquals(AlarmNotifications.ALERT_DURATION_MS, notification.timeoutAfter)
        assertEquals(0, notification.flags and Notification.FLAG_INSISTENT)
        assertNotNull(notification.contentIntent)
        assertNotNull(notification.fullScreenIntent)
        assertEquals("Dismiss", notification.actions.single().title.toString())
        val channel = notifications.getNotificationChannel(AlarmNotifications.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull(channel.sound) // Gain/looping are owned by the shared burst player.
        assertEquals(AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
    }

    @Test fun disablingActiveReminderCancelsNotificationAndFutureAlarm() = runBlocking {
        val reminder = saved()
        val occurrence = coordinator.scheduled(reminder.id, false)!!
        coordinator.deliver(occurrence, occurrence.scheduledTime)
        coordinator.setEnabled(reminder.id, false)
        assertEquals(0, notifications.activeNotifications.size)
        assertNull(coordinator.scheduled(reminder.id, false))
        assertNull(coordinator.active(reminder.id, false, occurrence.token))
    }

    @Test fun staleDismissCannotCancelNewOccurrence() = runBlocking {
        val reminder = saved()
        val first = coordinator.scheduled(reminder.id, false)!!
        coordinator.deliver(first, first.scheduledTime)
        val second = coordinator.scheduled(reminder.id, false)!!
        coordinator.deliver(second, second.scheduledTime)
        coordinator.dismiss(reminder.id, false, first.token)
        assertNotNull(coordinator.active(reminder.id, false, second.token))
        assertEquals(1, notifications.activeNotifications.size)
    }

    @Test fun blockedNotificationsDoNotSilentlyScheduleAlarms() = runBlocking {
        shadowOf(notifications).setNotificationsEnabled(false)
        val message = coordinator.save(reminder())
        assertTrue(message.contains("not scheduled"))
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(1, database.reminderDao().getAll().size)
    }

    @Test
    @Config(sdk = [31])
    fun missingExactAlarmAccessHasNoInexactFallbackAndGrantCanSchedule() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val message = coordinator.save(reminder())
        assertTrue(message.contains("not scheduled"))
        assertTrue(alarms.scheduledAlarms.isEmpty())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        coordinator.synchronize()
        val saved = database.reminderDao().getAll().single()
        assertNotNull(coordinator.scheduled(saved.id, false))
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test
    @Config(sdk = [34], shadows = [AlarmPermissionsTest.FullScreenNotificationManager::class])
    fun fullScreenDenialStillDeliversActionableNotification() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlarmPermissionsTest.FullScreenNotificationManager.allowed = false
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val reminder = saved()
        val occurrence = coordinator.scheduled(reminder.id, false)!!
        assertTrue(coordinator.deliver(occurrence, occurrence.scheduledTime))
        val notification = notifications.activeNotifications.single().notification
        assertNull(notification.fullScreenIntent)
        assertNotNull(notification.contentIntent)
        assertNotNull(notification.actions.single().actionIntent)
    }

    @Test fun appResumeKeepsExistingNormalAndTestOccurrenceIdentities() = runBlocking {
        val reminder = saved()
        coordinator.testInOneMinute(reminder.id, now)
        val normal = coordinator.scheduled(reminder.id, false)
        val test = coordinator.scheduled(reminder.id, true)
        val restarted = AlarmCoordinator(context, database.reminderDao())
        restarted.synchronize()
        assertEquals(normal, restarted.scheduled(reminder.id, false))
        assertEquals(test, restarted.scheduled(reminder.id, true))
        assertEquals(2, alarms.scheduledAlarms.size)
    }

    @Test fun blockedMedicationChannelPreventsScheduling() = runBlocking {
        AlarmNotifications.ensureChannel(context)
        val channel = notifications.getNotificationChannel(AlarmNotifications.CHANNEL_ID)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        notifications.createNotificationChannel(channel)
        assertFalse(AlarmNotifications.canNotify(context))
        assertTrue(coordinator.save(reminder()).contains("not scheduled"))
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun repeatedPendingTestsReplaceInsteadOfAccumulate() = runBlocking {
        val reminder = saved()
        val normal = coordinator.scheduled(reminder.id, false)
        coordinator.testInOneMinute(reminder.id, now)
        val old = coordinator.scheduled(reminder.id, true)!!
        coordinator.testInOneMinute(reminder.id, now + 2_000)
        val replacement = coordinator.scheduled(reminder.id, true)!!
        assertNotEquals(old.token, replacement.token)
        assertEquals(2, alarms.scheduledAlarms.size)
        assertEquals(listOf(replacement), coordinator.uiState().pendingTests)
        assertEquals(normal, coordinator.scheduled(reminder.id, false))
        assertFalse(coordinator.deliver(old, old.scheduledTime))
    }

    @Test fun activeTestCannotBeSilentlyReplacedByAnotherTestTap() = runBlocking {
        val reminder = saved()
        val normal = coordinator.scheduled(reminder.id, false)
        coordinator.testInOneMinute(reminder.id, now)
        val occurrence = coordinator.scheduled(reminder.id, true)!!
        coordinator.deliver(occurrence, occurrence.scheduledTime)
        assertTrue(coordinator.testInOneMinute(reminder.id).contains("already active"))
        assertTrue(coordinator.uiState().pendingTests.isEmpty())
        assertEquals(occurrence, coordinator.uiState().activeAlarms.single().occurrence)
        assertEquals(1, notifications.activeNotifications.size)
        assertEquals(normal, coordinator.scheduled(reminder.id, false))
    }

    @Test fun cancelPendingTestPreservesNormalScheduleAndRejectsStaleCancel() = runBlocking {
        val reminder = saved()
        val normal = coordinator.scheduled(reminder.id, false)
        coordinator.testInOneMinute(reminder.id, now)
        val old = coordinator.scheduled(reminder.id, true)!!
        coordinator.testInOneMinute(reminder.id, now + 2_000)
        val current = coordinator.scheduled(reminder.id, true)!!
        coordinator.cancelPendingTest(reminder.id, old.token)
        assertEquals(current, coordinator.scheduled(reminder.id, true))
        coordinator.cancelPendingTest(reminder.id, current.token)
        assertTrue(coordinator.uiState().pendingTests.isEmpty())
        assertEquals(normal, coordinator.scheduled(reminder.id, false))
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test fun mainScreenStateSurvivesNotificationTimeoutAndClearsOnDismiss() = runBlocking {
        val reminder = saved()
        coordinator.testInOneMinute(reminder.id, now)
        val occurrence = coordinator.scheduled(reminder.id, true)!!
        coordinator.deliver(occurrence, occurrence.scheduledTime)
        // Model Android removing the notification at its unchanged 30-second timeout.
        AlarmNotifications.cancel(context, reminder.id, true)
        assertEquals(occurrence, coordinator.uiState().activeAlarms.single().occurrence)
        coordinator.dismiss(reminder.id, true, occurrence.token)
        assertTrue(coordinator.uiState().activeAlarms.isEmpty())
    }

    @Test fun earlyNormalAndTestDeliveriesAreRearmedWithoutChangingIdentity() = runBlocking {
        val reminder = saved()
        coordinator.testInOneMinute(reminder.id, now)
        for (test in listOf(true, false)) {
            val occurrence = coordinator.scheduled(reminder.id, test)!!
            val manager = context.getSystemService(AlarmManager::class.java)
            // Model Android consuming the one-shot registration before calling the receiver.
            val registration = alarms.scheduledAlarms.single { it.triggerAtTime == occurrence.scheduledTime }
            manager.cancel(checkNotNull(registration.operation))
            assertFalse(coordinator.deliver(occurrence, occurrence.scheduledTime - 1))
            assertEquals(occurrence, coordinator.scheduled(reminder.id, test))
            assertTrue(alarms.scheduledAlarms.any { it.triggerAtTime == occurrence.scheduledTime })
            assertNull(coordinator.active(reminder.id, test, occurrence.token))
            assertTrue(coordinator.deliver(occurrence, occurrence.scheduledTime))
            coordinator.dismiss(reminder.id, test, occurrence.token)
        }
        assertEquals(reminder.startTimeMillis + 8 * 3_600_000L,
            coordinator.scheduled(reminder.id, false)!!.scheduledTime)
    }

    @Test fun shortNoticeEditReplacesAlarmAndEarlyStaleBroadcastCannotRestoreOldSchedule() = runBlocking {
        val reminder = saved()
        val old = coordinator.scheduled(reminder.id, false)!!
        val edited = reminder.copy(startTimeMillis = now + 180_000)
        coordinator.save(edited)
        val replacement = coordinator.scheduled(reminder.id, false)!!
        assertFalse(coordinator.deliver(old, old.scheduledTime - 1))
        assertEquals(edited.startTimeMillis, replacement.scheduledTime)
        assertEquals(replacement, coordinator.scheduled(reminder.id, false))
        assertEquals(listOf(replacement), coordinator.uiState().pendingDoses)
        assertEquals(1, alarms.scheduledAlarms.size)
        assertTrue(coordinator.deliver(replacement, replacement.scheduledTime))
    }

    @Test fun elapsedStartDisplaysActualNextAnchoredDoseAndDisabledReminderHasNone() = runBlocking {
        coordinator.save(reminder().copy(startTimeMillis = now - 60_000))
        val reminder = database.reminderDao().getAll().single()
        val next = coordinator.uiState().pendingDoses.single()
        assertEquals(reminder.startTimeMillis + 8 * 3_600_000L, next.scheduledTime)
        coordinator.setEnabled(reminder.id, false)
        assertTrue(coordinator.uiState().pendingDoses.isEmpty())
    }

    @Test fun foregroundObserverSeesDeliveryAndDismissalFromOtherCoordinator() = runBlocking {
        val reminder = saved()
        val states = kotlinx.coroutines.channels.Channel<AlarmUiState>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            coordinator.observeState().collect { states.send(it) }
        }
        try {
            kotlinx.coroutines.withTimeout(5_000) { states.receive() }
            val receiverCoordinator = AlarmCoordinator(context, database.reminderDao())
            receiverCoordinator.testInOneMinute(reminder.id, now)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            kotlinx.coroutines.withTimeout(5_000) {
                while (states.receive().pendingTests.isEmpty()) { }
            }
            val occurrence = receiverCoordinator.scheduled(reminder.id, true)!!
            receiverCoordinator.deliver(occurrence, occurrence.scheduledTime)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            kotlinx.coroutines.withTimeout(5_000) {
                while (states.receive().activeAlarms.none { it.occurrence == occurrence }) { }
            }
            receiverCoordinator.dismiss(reminder.id, true, occurrence.token)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            kotlinx.coroutines.withTimeout(5_000) {
                while (states.receive().activeAlarms.isNotEmpty()) { }
            }
        } finally {
            job.cancel()
            job.join()
            states.close()
        }
    }
}
