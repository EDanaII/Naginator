package com.EDanaII.pillreminder

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class DirectBootAlarmActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun lockedFullScreenContentAndDismissWorkWithoutRoomOrMedicationDetails() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        users.setUserUnlocked(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReflectionHelpers.setStaticField(ReminderDatabase::class.java, "instance", null)
        context.deleteDatabase("pill-reminders.db")
        DirectBootAlarmStore(context).preferences.edit().clear().commit()
        for (name in listOf("alarm_occurrences", "alarm_configurations")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        val database = ReminderDatabase.getInstance(context)
        val coordinator = AlarmCoordinator(context)
        coordinator.save(Reminder(name = "Private medication", instructions = "Private instructions",
            startTimeMillis = System.currentTimeMillis() + 300_000, doseIntervalHours = 8, nagIntervalMinutes = 17))
        val reminder = database.reminderDao().getAll().single()
        val occurrence = coordinator.scheduled(reminder.id, false)!!
        database.close()
        users.setUserUnlocked(false)
        try {
            assertTrue(coordinator.deliver(occurrence, occurrence.scheduledTime))
            val next = coordinator.scheduled(reminder.id, false)!!
            val notification = context.getSystemService(NotificationManager::class.java)
                .activeNotifications.single().notification
            if (Build.VERSION.SDK_INT < 29) assertNotNull(notification.fullScreenIntent)
            val intent = Intent(context, MedicationAlarmActivity::class.java)
                .putExtra(AlarmCoordinator.EXTRA_OCCURRENCE,
                    AlarmCoordinator.encode(occurrence.copy(configuration = "")))
            val controller = Robolectric.buildActivity(MedicationAlarmActivity::class.java, intent).setup().visible()
            try {
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText("Unlock your phone to view medication details.")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Unlock your phone to view medication details.").assertIsDisplayed()
                compose.onNodeWithText("Private medication").assertDoesNotExist()
                compose.onNodeWithText("Private instructions").assertDoesNotExist()
                compose.onNodeWithText("Dismiss").performScrollTo().performClick()
                compose.waitUntil(5_000) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    controller.get().isFinishing
                }
                assertNull(coordinator.active(reminder.id, false, occurrence.token))
                assertEquals(next, coordinator.scheduled(reminder.id, false))
                assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
            } finally {
                controller.pause().stop().destroy()
            }
        } finally {
            users.setUserUnlocked(true)
            ReflectionHelpers.setStaticField(ReminderDatabase::class.java, "instance", null)
        }
    }
}
