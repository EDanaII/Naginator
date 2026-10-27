package com.EDanaII.pillreminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.UserManager
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecurringNagTest {
    private lateinit var context: Context
    private lateinit var database: ReminderDatabase
    private lateinit var coordinator: AlarmCoordinator
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val store get() = DirectBootAlarmStore(context)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        store.preferences.edit().clear().commit()
        context.getSharedPreferences("alarm_configurations", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("alarm_occurrences", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, ReminderDatabase::class.java).build()
        coordinator = AlarmCoordinator(context, database.reminderDao())
    }

    @After fun tearDown() {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        database.close()
    }

    private suspend fun save(vibration: Boolean = true, escalation: Boolean = true): Reminder {
        coordinator.save(Reminder(name = "Private medicine", instructions = "Private dose",
            startTimeMillis = System.currentTimeMillis() + 300_000, doseIntervalHours = 8,
            nagIntervalMinutes = 7, vibrationEnabled = vibration, escalationEnabled = escalation,
            alarmSoundUri = "content://media/private-tone"))
        return database.reminderDao().getAll().single()
    }

    @Test fun savedSelectionAndUnlockedPlaybackAreLoggedWithoutMedicationDetails() = runBlocking {
        val reminder = save()
        val dose = coordinator.scheduled(reminder.id, false)!!
        coordinator.deliver(dose, dose.scheduledTime)
        val state = coordinator.nagState(dose.token)!!
        assertEquals(reminder.alarmSoundUri, coordinator.playback(dose.token, state.lastAlertTime)!!.soundUri)
        val logs = ShadowLog.getLogsForTag(AlarmSoundDiagnostics.TAG).joinToString { it.msg }
        assertTrue(logs.contains("REMINDER_SAVED id=${reminder.id} selection=CUSTOM"))
        assertTrue(logs.contains("selection=CUSTOM unlocked=true level=0"))
        assertFalse(logs.contains("private-tone"))
        assertFalse(logs.contains("Private medicine"))
        assertFalse(logs.contains("Private dose"))
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        assertNull(AlarmCoordinator(context).playback(dose.token, state.lastAlertTime)!!.soundUri)
        assertTrue(ShadowLog.getLogsForTag(AlarmSoundDiagnostics.TAG).any {
            it.msg.contains("selection=NULL unlocked=false")
        })
    }

    private fun blockActiveChannel(blocked: Boolean) {
        AlarmNotifications.ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(AlarmNotifications.CHANNEL_ID)
        channel.importance = if (blocked) NotificationManager.IMPORTANCE_NONE else NotificationManager.IMPORTANCE_HIGH
        manager.createNotificationChannel(channel)
    }

    private fun consumeRegisteredAlarms() {
        alarms.scheduledAlarms.toList().forEach {
            context.getSystemService(AlarmManager::class.java).cancel(it.operation!!)
        }
    }

    private fun assertDiagnostic(stage: String, reason: String) {
        assertTrue(ShadowLog.getLogsForTag("MedicationAlarm").any {
            it.msg.contains(stage) && it.msg.contains(reason)
        })
    }

    @Test fun blockedLegacyChannelCannotSuppressSchedulingDeliveryNagsOrPlayback() = runBlocking {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("medication_alarms", "Medication alarms",
            NotificationManager.IMPORTANCE_NONE))
        val reminder = save()
        assertTrue(AlarmNotifications.canNotify(context))
        val dose = coordinator.scheduled(reminder.id, false)!!
        coordinator.testEscalation(reminder.id)
        assertNotNull(coordinator.scheduled(reminder.id, true))
        consumeRegisteredAlarms()
        assertTrue(coordinator.deliver(dose, dose.scheduledTime))
        val state = coordinator.nagState(dose.token)!!
        assertNotNull(coordinator.playback(dose.token, state.lastAlertTime))
        assertTrue(coordinator.nag(dose, state.nextNagTime, state.nextNagTime))
        val advanced = coordinator.nagState(dose.token)!!
        assertNotNull(coordinator.playback(dose.token, advanced.lastAlertTime))
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtTime == advanced.nextNagTime })
        assertEquals(NotificationManager.IMPORTANCE_NONE,
            manager.getNotificationChannel("medication_alarms").importance)
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val locked = AlarmCoordinator(context)
        consumeRegisteredAlarms()
        locked.synchronize()
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtTime == advanced.nextNagTime })
        assertNotNull(locked.playback(dose.token, advanced.lastAlertTime))
    }

    @Test fun blockedActiveChannelSuppressesSchedulingAndBothTestActions() = runBlocking {
        blockActiveChannel(true)
        val reminder = save()
        assertFalse(AlarmNotifications.canNotify(context))
        assertNull(coordinator.scheduled(reminder.id, false))
        assertTrue(coordinator.testInOneMinute(reminder.id).contains("medication_alerts_v2"))
        assertTrue(coordinator.testEscalation(reminder.id).contains("medication_alerts_v2"))
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun blockedAppNotificationsSuppressSchedulingAndBothTestActions() = runBlocking {
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val reminder = save()
        assertFalse(AlarmNotifications.canNotify(context))
        assertNull(coordinator.scheduled(reminder.id, false))
        assertTrue(coordinator.testInOneMinute(reminder.id).contains("app-wide"))
        assertTrue(coordinator.testEscalation(reminder.id).contains("app-wide"))
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test @Config(sdk = [33]) fun deniedRuntimeNotificationPermissionReportsSpecificReason() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(AlarmNotifications.canNotify(context))
        assertEquals("POST_NOTIFICATIONS permission denied", AlarmNotifications.suppressionReason(context))
        shadowOf(application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(AlarmNotifications.canNotify(context))
    }

    @Test fun blockedInitialRetainsStateLogsReasonAndSynchronizeRestoresWork() = runBlocking {
        val reminder = save()
        val dose = coordinator.scheduled(reminder.id, false)!!
        consumeRegisteredAlarms()
        blockActiveChannel(true)
        assertFalse(coordinator.deliver(dose, dose.scheduledTime))
        val retained = coordinator.nagState(dose.token)!!
        val nextDose = coordinator.scheduled(reminder.id, false)!!
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNull(coordinator.playback(dose.token, retained.lastAlertTime))
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        assertDiagnostic("Initial alert suppressed", "medication_alerts_v2")
        assertDiagnostic("Initial follow-up registration skipped", "nextNag=${retained.nextNagTime}")
        assertDiagnostic("Playback request suppressed", "medication_alerts_v2")
        blockActiveChannel(false)
        coordinator.synchronize()
        assertEquals(retained, coordinator.nagState(dose.token))
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtTime == retained.nextNagTime })
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtTime == nextDose.scheduledTime })
    }

    @Test fun blockedNagRetainsAdvancedStateAndLogsMissingRegistration() = runBlocking {
        val reminder = save()
        val dose = coordinator.scheduled(reminder.id, false)!!
        coordinator.deliver(dose, dose.scheduledTime)
        val before = coordinator.nagState(dose.token)!!
        consumeRegisteredAlarms()
        AlarmNotifications.cancel(context, reminder.id, false, dose.token)
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(coordinator.nag(dose, before.nextNagTime, before.nextNagTime))
        val retained = coordinator.nagState(dose.token)!!
        assertEquals(1, retained.level)
        assertEquals(before.nextNagTime + 7 * 60_000, retained.nextNagTime)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNull(coordinator.playback(dose.token, retained.lastAlertTime))
        assertTrue(manager.activeNotifications.isEmpty())
        assertDiagnostic("Nag suppressed", "app-wide")
        assertDiagnostic("Next nag registration skipped", "nextNag=${retained.nextNagTime}")
        assertDiagnostic("Playback request suppressed", "app-wide")
        shadowOf(manager).setNotificationsEnabled(true)
        coordinator.synchronize()
        assertEquals(retained, coordinator.nagState(dose.token))
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtTime == retained.nextNagTime })
    }

    @Test fun legacyOccurrenceWithoutOverridesStillDecodes() {
        val legacy = """{"id":1,"at":1000,"token":"legacy","test":true,"configuration":"revision"}"""
        val occurrence = AlarmCoordinator.decode(legacy)!!
        assertNull(occurrence.nagIntervalOverrideMinutes)
        assertFalse(occurrence.forceEscalation)
        assertEquals(occurrence, AlarmCoordinator.decode(AlarmCoordinator.encode(occurrence)))
    }

    @Test fun standardTestRetainsConfiguredIntervalAndDisabledEscalation() = runBlocking {
        val reminder = save(vibration = false, escalation = false)
        val dose = coordinator.scheduled(reminder.id, false)
        val now = System.currentTimeMillis()
        coordinator.testInOneMinute(reminder.id, now)
        val test = coordinator.scheduled(reminder.id, true)!!
        assertEquals(now + 60_000, test.scheduledTime)
        assertNull(test.nagIntervalOverrideMinutes)
        assertFalse(test.forceEscalation)
        coordinator.deliver(test, test.scheduledTime)
        val initial = coordinator.nagState(test.token)!!
        assertEquals(test.scheduledTime + 7 * 60_000, initial.nextNagTime)
        coordinator.nag(test, initial.nextNagTime, initial.nextNagTime)
        val state = coordinator.nagState(test.token)!!
        assertEquals(0, state.level)
        val playback = coordinator.playback(test.token, state.lastAlertTime)!!
        assertFalse(playback.escalation)
        assertFalse(playback.vibration)
        assertEquals(reminder.alarmSoundUri, playback.soundUri)
        assertEquals(dose, coordinator.scheduled(reminder.id, false))
        assertEquals(reminder, database.reminderDao().findById(reminder.id))
    }

    @Test fun acceleratedTestEscalatesIndependentlyWithoutChangingReminderOrRealOccurrence() = runBlocking {
        val reminder = save(escalation = false)
        val dose = coordinator.scheduled(reminder.id, false)!!
        coordinator.deliver(dose, dose.scheduledTime)
        val realState = coordinator.nagState(dose.token)!!
        val nextDose = coordinator.scheduled(reminder.id, false)
        val now = dose.scheduledTime
        coordinator.testEscalation(reminder.id, now)
        val test = coordinator.scheduled(reminder.id, true)!!
        assertEquals(now + 60_000, test.scheduledTime)
        assertEquals(1, test.nagIntervalOverrideMinutes)
        assertTrue(test.forceEscalation)
        coordinator.deliver(test, test.scheduledTime)
        assertEquals(0, coordinator.nagState(test.token)!!.level)
        for (level in listOf(1, 2, 3, 3)) {
            val before = coordinator.nagState(test.token)!!
            assertEquals(before.lastAlertTime + 60_000, before.nextNagTime)
            assertTrue(coordinator.nag(test, before.nextNagTime, before.nextNagTime))
            val after = coordinator.nagState(test.token)!!
            assertEquals(level, after.level)
            val playback = coordinator.playback(test.token, after.lastAlertTime)!!
            assertTrue(playback.escalation)
            assertTrue(playback.vibration)
            assertEquals(reminder.alarmSoundUri, playback.soundUri)
            assertEquals(realState, coordinator.nagState(dose.token))
        }
        val testState = coordinator.nagState(test.token)!!
        coordinator.nag(dose, realState.nextNagTime, realState.nextNagTime)
        assertEquals(0, coordinator.nagState(dose.token)!!.level)
        assertEquals(testState, coordinator.nagState(test.token))
        coordinator.dismiss(reminder.id, true, test.token)
        assertNull(coordinator.nagState(test.token))
        assertNotNull(coordinator.nagState(dose.token))
        assertEquals(nextDose, coordinator.scheduled(reminder.id, false))
        assertFalse(coordinator.nag(test, testState.nextNagTime, testState.nextNagTime))
        assertFalse(alarms.scheduledAlarms.any {
            shadowOf(it.operation!!).savedIntent.data.toString().contains(test.token)
        })
        assertEquals(reminder, database.reminderDao().findById(reminder.id))
    }

    @Test fun acceleratedPendingAndActiveStateSurviveLockedReboot() = runBlocking {
        val reminder = save(escalation = false)
        val nextDose = coordinator.scheduled(reminder.id, false)
        coordinator.testEscalation(reminder.id)
        val test = coordinator.scheduled(reminder.id, true)!!
        database.close()
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        alarms.scheduledAlarms.toList().forEach {
            context.getSystemService(AlarmManager::class.java).cancel(it.operation!!)
        }
        val locked = AlarmCoordinator(context)
        locked.synchronize()
        assertEquals(test, locked.scheduled(reminder.id, true))
        locked.deliver(test, test.scheduledTime)
        val initial = locked.nagState(test.token)!!
        assertEquals(test.scheduledTime + 60_000, initial.nextNagTime)
        locked.nag(test, initial.nextNagTime, initial.nextNagTime)
        val before = locked.nagState(test.token)!!
        alarms.scheduledAlarms.toList().forEach {
            context.getSystemService(AlarmManager::class.java).cancel(it.operation!!)
        }
        val restarted = AlarmCoordinator(context)
        restarted.synchronize()
        assertEquals(before, restarted.nagState(test.token))
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtTime == before.nextNagTime })
        restarted.nag(test, before.nextNagTime, before.nextNagTime)
        val after = restarted.nagState(test.token)!!
        assertEquals(2, after.level)
        assertEquals(before.nextNagTime + 60_000, after.nextNagTime)
        assertTrue(restarted.playback(test.token, after.lastAlertTime)!!.escalation)
        assertEquals(nextDose, restarted.scheduled(reminder.id, false))
    }

    @Test fun editAndDeleteCancelAcceleratedNagChains() = runBlocking {
        val reminder = save()
        suspend fun activeTest(): NagState {
            coordinator.testEscalation(reminder.id)
            val test = coordinator.scheduled(reminder.id, true)!!
            coordinator.deliver(test, test.scheduledTime)
            return coordinator.nagState(test.token)!!
        }
        val edited = activeTest()
        coordinator.save(reminder.copy(instructions = "Changed"))
        assertNull(coordinator.nagState(edited.occurrence.token))
        assertFalse(coordinator.nag(edited.occurrence, edited.nextNagTime, edited.nextNagTime))
        val deleted = activeTest()
        coordinator.delete(reminder.id)
        assertNull(coordinator.nagState(deleted.occurrence.token))
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun initialAlertCreatesOneNagAndKeepsNextDoseAnchored() = runBlocking {
        val reminder = save()
        val dose = coordinator.scheduled(reminder.id, false)!!
        assertTrue(coordinator.deliver(dose, dose.scheduledTime))
        val nag = coordinator.nagState(dose.token)!!
        assertEquals(0, nag.level)
        assertEquals(dose.scheduledTime + 7 * 60_000, nag.nextNagTime)
        assertEquals(dose.scheduledTime + 8 * 3_600_000, coordinator.scheduled(reminder.id, false)!!.scheduledTime)
        assertEquals(2, alarms.scheduledAlarms.size)
        assertFalse(coordinator.deliver(dose, dose.scheduledTime))
        assertEquals(nag, coordinator.nagState(dose.token))
    }

    @Test fun repeatedNagsEscalateToCeilingAndRejectDuplicatesWithoutMovingDose() = runBlocking {
        val reminder = save()
        val dose = coordinator.scheduled(reminder.id, false)!!
        coordinator.deliver(dose, dose.scheduledTime)
        val nextDose = coordinator.scheduled(reminder.id, false)
        for (level in listOf(1, 2, 3, 3, 3)) {
            val before = coordinator.nagState(dose.token)!!
            assertTrue(coordinator.nag(dose, before.nextNagTime, before.nextNagTime))
            val after = coordinator.nagState(dose.token)!!
            assertEquals(level, after.level)
            assertEquals(before.nextNagTime + 7 * 60_000, after.nextNagTime)
            assertFalse(coordinator.nag(dose, before.nextNagTime, before.nextNagTime))
            assertEquals(after, coordinator.nagState(dose.token))
        }
        assertEquals(nextDose, coordinator.scheduled(reminder.id, false))
        assertEquals(2, alarms.scheduledAlarms.size)
    }

    @Test fun timeoutDoesNotAcknowledgeAndDismissStopsOnlyThisOccurrence() = runBlocking {
        val reminder = save()
        val first = coordinator.scheduled(reminder.id, false)!!
        coordinator.deliver(first, first.scheduledTime)
        val firstNag = coordinator.nagState(first.token)!!
        AlarmNotifications.cancel(context, reminder.id, false, first.token) // Notification timeout.
        assertEquals(firstNag, coordinator.nagState(first.token))
        val second = coordinator.scheduled(reminder.id, false)!!
        coordinator.deliver(second, second.scheduledTime)
        assertEquals(2, coordinator.uiState().activeAlarms.size)
        assertEquals(0, coordinator.nagState(second.token)!!.level)
        val nextDose = coordinator.scheduled(reminder.id, false)
        coordinator.dismiss(reminder.id, false, first.token)
        assertNull(coordinator.nagState(first.token))
        assertNotNull(coordinator.nagState(second.token))
        assertFalse(coordinator.nag(first, firstNag.nextNagTime, second.scheduledTime))
        assertEquals(nextDose, coordinator.scheduled(reminder.id, false))
        assertEquals(2, alarms.scheduledAlarms.size)
    }

    @Test fun disabledEscalationUsesInitialLevelAndDisabledVibrationIsRetained() = runBlocking {
        val reminder = save(vibration = false, escalation = false)
        val dose = coordinator.scheduled(reminder.id, false)!!
        coordinator.deliver(dose, dose.scheduledTime)
        repeat(5) {
            val state = coordinator.nagState(dose.token)!!
            coordinator.nag(dose, state.nextNagTime, state.nextNagTime)
            val updated = coordinator.nagState(dose.token)!!
            val playback = coordinator.playback(dose.token, updated.lastAlertTime)!!
            assertEquals(0, updated.level)
            assertFalse(playback.vibration)
            assertFalse(playback.escalation)
            assertEquals(.25f, AlertEscalation.level(updated.level, playback.escalation).gain)
        }
    }

    @Test fun lockedRebootRetainsLevelAndOriginalNagTimeThenCatchesUpOnce() = runBlocking {
        val reminder = save()
        val dose = coordinator.scheduled(reminder.id, false)!!
        coordinator.deliver(dose, dose.scheduledTime)
        val initial = coordinator.nagState(dose.token)!!
        coordinator.nag(dose, initial.nextNagTime, initial.nextNagTime)
        val before = coordinator.nagState(dose.token)!!
        val nextDose = coordinator.scheduled(reminder.id, false)
        alarms.scheduledAlarms.forEach { context.getSystemService(AlarmManager::class.java).cancel(it.operation!!) }
        database.close()
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val locked = AlarmCoordinator(context)
        locked.synchronize()
        locked.synchronize()
        assertEquals(before, locked.nagState(dose.token))
        assertEquals(2, alarms.scheduledAlarms.size)
        assertTrue(alarms.scheduledAlarms.any { it.triggerAtTime == before.nextNagTime })
        val now = before.nextNagTime + 3 * 86_400_000L
        assertTrue(locked.nag(dose, before.nextNagTime, now))
        val updated = locked.nagState(dose.token)!!
        assertEquals(2, updated.level) // One catch-up, not hundreds of elapsed alerts.
        assertEquals(now + 7 * 60_000, updated.nextNagTime)
        assertEquals(nextDose, locked.scheduled(reminder.id, false))
        val request = locked.playback(dose.token, now)!!
        assertFalse(request.unlocked)
        assertNull(request.soundUri)
        assertFalse(store.preferences.all.values.joinToString().contains("Private"))
        assertFalse(store.preferences.all.values.joinToString().contains("private-tone"))
        locked.dismiss(reminder.id, false, dose.token)
        assertNull(locked.nagState(dose.token))
        locked.synchronize()
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test fun earlyNagRearmsAndDisableDeleteInvalidateEveryOccurrence() = runBlocking {
        val reminder = save()
        val first = coordinator.scheduled(reminder.id, false)!!
        coordinator.deliver(first, first.scheduledTime)
        val before = coordinator.nagState(first.token)!!
        assertFalse(coordinator.nag(first, before.nextNagTime, before.nextNagTime - 1))
        assertEquals(before, coordinator.nagState(first.token))
        val second = coordinator.scheduled(reminder.id, false)!!
        coordinator.deliver(second, second.scheduledTime)
        coordinator.setEnabled(reminder.id, false)
        assertTrue(coordinator.uiState().activeAlarms.isEmpty())
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertFalse(coordinator.nag(first, before.nextNagTime, second.scheduledTime))
        coordinator.setEnabled(reminder.id, true)
        val replacement = coordinator.scheduled(reminder.id, false)!!
        coordinator.deliver(replacement, replacement.scheduledTime)
        val state = coordinator.nagState(replacement.token)!!
        coordinator.delete(reminder.id)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertFalse(coordinator.nag(replacement, state.nextNagTime, state.nextNagTime))
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test fun migratesOldIdKeyedActiveWithoutResettingTokensOrChangingDose() = runBlocking {
        val reminder = save()
        val dose = coordinator.scheduled(reminder.id, false)!!
        store.preferences.edit().putString("active:${reminder.id}:false", AlarmCoordinator.encode(dose)).commit()
        coordinator.synchronize()
        assertEquals(dose, coordinator.active(reminder.id, false, dose.token)!!.occurrence)
        assertFalse(store.preferences.contains("active:${reminder.id}:false"))
        assertTrue(store.preferences.contains("active:${dose.token}"))
        val before = coordinator.nagState(dose.token)!!
        coordinator.synchronize()
        assertEquals(before, coordinator.nagState(dose.token))
        assertEquals(dose, coordinator.scheduled(reminder.id, false))
    }
}
