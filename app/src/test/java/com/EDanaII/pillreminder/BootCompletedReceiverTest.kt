package com.EDanaII.pillreminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Build
import android.os.Looper
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
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BootCompletedReceiverTest {
    private lateinit var context: Context
    private lateinit var database: ReminderDatabase
    private lateinit var coordinator: AlarmCoordinator
    private val manager get() = context.getSystemService(AlarmManager::class.java)
    private val alarms get() = shadowOf(manager)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(android.os.UserManager::class.java)).setUserUnlocked(true)
        DirectBootAlarmStore(context).preferences.edit().clear().commit()
        context.getSharedPreferences("alarm_configurations", Context.MODE_PRIVATE).edit().clear().commit()
        // The production singleton outlives individual test instances in Robolectric's sandbox.
        ReflectionHelpers.setStaticField(ReminderDatabase::class.java, "instance", null)
        context.deleteDatabase("pill-reminders.db")
        context.getSharedPreferences("alarm_occurrences", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        if (Build.VERSION.SDK_INT >= 33) {
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        database = ReminderDatabase.getInstance(context)
        coordinator = AlarmCoordinator(context)
    }

    @After fun tearDown() {
        database.close()
        ReflectionHelpers.setStaticField(ReminderDatabase::class.java, "instance", null)
    }

    private suspend fun save(
        enabled: Boolean = true,
        startTimeMillis: Long = System.currentTimeMillis() + 300_000
    ): Reminder {
        coordinator.save(Reminder(name = "Medication", instructions = "Take with food",
            startTimeMillis = startTimeMillis,
            doseIntervalHours = 8, nagIntervalMinutes = 15, enabled = enabled))
        return database.reminderDao().getAll().last()
    }

    private fun loseOsAlarms() {
        // A reboot removes registrations, but not Room records or occurrence preferences.
        alarms.scheduledAlarms.forEach { manager.cancel(checkNotNull(it.operation)) }
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    private fun boot() {
        val finished = CountDownLatch(1)
        // Resolve the manifest receiver and wait for its goAsync result to be finished.
        context.sendOrderedBroadcast(
            Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context.packageName), null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { finished.countDown() }
            }, Handler(Looper.getMainLooper()), 0, null, null)
        awaitCondition("Boot broadcast did not finish") { finished.count == 0L }
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val pause = CountDownLatch(1)
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            pause.await(10, TimeUnit.MILLISECONDS)
        }
        fail(message)
    }

    @Test fun manifestDeclaresBootPermissionAndEnabledPrivateReceiver() {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PERMISSIONS)
        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.RECEIVE_BOOT_COMPLETED))
        val receivers = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context.packageName), 0)
        val receiver = receivers.single { it.activityInfo.name == BootCompletedReceiver::class.java.name }
        assertTrue(receiver.activityInfo.enabled)
        assertFalse(receiver.activityInfo.exported)
        assertTrue(receiver.activityInfo.directBootAware)
    }

    @Test
    @Config(sdk = [26, 31, 34])
    fun bootRestoresEnabledRemindersWithoutChangingRecordsOrOccurrenceIdentities() = runBlocking {
        val enabled = save()
        save(enabled = false)
        coordinator.testInOneMinute(enabled.id)
        val records = database.reminderDao().getAll()
        val dose = coordinator.scheduled(enabled.id, false)!!
        val test = coordinator.scheduled(enabled.id, true)!!
        loseOsAlarms()

        boot()

        val recovered = AlarmCoordinator(context)
        assertEquals(records, database.reminderDao().getAll())
        assertEquals(dose, recovered.scheduled(enabled.id, false))
        assertEquals(test, recovered.scheduled(enabled.id, true))
        assertEquals(setOf(dose.scheduledTime, test.scheduledTime),
            alarms.scheduledAlarms.map { it.triggerAtTime }.toSet())
        assertEquals(2, alarms.scheduledAlarms.size)
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun repeatedBootRecoveryReplacesRegistrationsInsteadOfDuplicatingThem() = runBlocking {
        val reminder = save()
        val original = coordinator.scheduled(reminder.id, false)!!
        loseOsAlarms()

        repeat(3) { boot() }

        assertEquals(original, coordinator.scheduled(reminder.id, false))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(original.scheduledTime, alarms.scheduledAlarms.single().triggerAtTime)
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun overdueDoseKeepsOriginalTimestampAndDeliversOnceWithoutMovingAnchor() = runBlocking {
        verifyOverdueRecovery(Duration.ofMinutes(6))
    }

    @Test fun longShutdownCatchesUpOnceAndSkipsElapsedIntervals() = runBlocking {
        verifyOverdueRecovery(Duration.ofHours(25))
    }

    private suspend fun verifyOverdueRecovery(downtime: Duration) {
        val recoveredAt = System.currentTimeMillis()
        val reminder = save(startTimeMillis = recoveredAt + 300_000 - downtime.toMillis())
        // Seed the pending occurrence that would have been persisted five minutes before shutdown.
        // Robolectric's elapsed-time clock does not advance the application's wall clock.
        val original = coordinator.scheduled(reminder.id, false)!!.copy(
            scheduledTime = reminder.startTimeMillis)
        DirectBootAlarmStore(context).preferences.edit()
            .putString("scheduled:${reminder.id}:false", AlarmCoordinator.encode(original)).commit()
        loseOsAlarms()
        assertTrue(original.scheduledTime < recoveredAt)

        boot()

        val registration = alarms.scheduledAlarms.single()
        assertEquals(original, coordinator.scheduled(reminder.id, false))
        // Explicitly verify that recovery passes the PAST timestamp to AlarmManager unchanged.
        assertEquals(original.scheduledTime, registration.triggerAtTime)
        assertEquals(AlarmManager.RTC_WAKEUP, registration.type)
        assertEquals(0L, registration.windowLengthMs)
        assertTrue(registration.allowWhileIdle)

        // Emulate Android delivering the overdue alarm promptly, through the real PendingIntent.
        // Robolectric does not model device-specific AlarmManager dispatch latency.
        alarms.fireAlarm(registration)
        awaitCondition("Overdue dose was not delivered") { notifications.activeNotifications.size == 1 }
        assertEquals(original, coordinator.active(reminder.id, false, original.token)!!.occurrence)
        val next = coordinator.scheduled(reminder.id, false)!!
        assertEquals(DoseSchedule.next(reminder.startTimeMillis, reminder.doseIntervalHours, recoveredAt),
            next.scheduledTime)
        assertEquals(2, alarms.scheduledAlarms.size) // Next anchored dose plus this occurrence's nag.
        assertTrue(next.scheduledTime > recoveredAt)
        assertFalse(coordinator.deliver(original, recoveredAt))
        assertEquals(1, notifications.activeNotifications.size)
        assertEquals(next, coordinator.scheduled(reminder.id, false))
        assertEquals(reminder, database.reminderDao().findById(reminder.id))
    }

    @Test fun recoveryFinishesBroadcastEvenWhenDatabaseReadFails() {
        database.close()
        boot()
        assertTrue(ShadowLog.getLogsForTag("MedicationAlarm").any {
            it.msg == "Unable to restore alarms after boot" && it.throwable != null
        })
    }

    @Test fun unrelatedActionsDoNotStartRecovery() {
        val receiver = BootCompletedReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        assertFalse(shadowOf(receiver).wentAsync())
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    @Config(sdk = [31])
    fun missingExactAlarmPermissionDoesNotCrashBootOrChangeReminderDefinition() = runBlocking {
        val reminder = save()
        loseOsAlarms()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        boot()
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(reminder, database.reminderDao().findById(reminder.id))
    }
}
