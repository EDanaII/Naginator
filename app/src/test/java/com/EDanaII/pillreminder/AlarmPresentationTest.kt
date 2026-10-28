package com.EDanaII.pillreminder

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AlarmPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val reminder = Reminder(id = 1, name = "Morning pills", instructions = "Pill A — 2 tablets",
        startTimeMillis = 1_800_000_000_000, doseIntervalHours = 8, nagIntervalMinutes = 15)
    private val occurrence = AlarmOccurrence(1, reminder.startTimeMillis, "test-token", true, "")
    private val alarm = ActiveAlarm(reminder, occurrence)

    @Test fun foregroundDeliveryDisplaysMedicationAndLargeDismiss() {
        val state = mutableStateOf(AlarmUiState())
        val dismissed = mutableListOf<AlarmOccurrence>()
        compose.setContent {
            MaterialTheme {
                ForegroundAlarmHost(state.value.activeAlarms, true, false, {
                    dismissed.add(it)
                    state.value = AlarmUiState()
                }) { onOpen ->
                    ReminderListScreen(reminders = listOf(reminder), alarmUiState = state.value,
                        onOpenAlarm = onOpen, onDismissAlarm = {
                            dismissed.add(it)
                            state.value = AlarmUiState()
                        })
                }
            }
        }
        compose.runOnIdle { state.value = AlarmUiState(activeAlarms = listOf(alarm)) }
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
        compose.onNodeWithText("Scheduled:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("Dismiss").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(occurrence), dismissed) }
    }

    @Test fun showRemindersPreservesAlarmAndMainControlsOpenAndDismissIt() {
        val state = mutableStateOf(AlarmUiState(activeAlarms = listOf(alarm)))
        val dismissed = mutableListOf<AlarmOccurrence>()
        compose.setContent {
            MaterialTheme {
                ForegroundAlarmHost(state.value.activeAlarms, true, false, {
                    dismissed.add(it)
                    state.value = AlarmUiState()
                }) { onOpen ->
                    ReminderListScreen(reminders = listOf(reminder), alarmUiState = state.value,
                        onOpenAlarm = onOpen, onDismissAlarm = {
                            dismissed.add(it)
                            state.value = AlarmUiState()
                        })
                }
            }
        }
        compose.onNodeWithText("Show reminders").performClick()
        compose.runOnIdle { assertEquals(0, dismissed.size) }
        compose.onNodeWithText("Open alarm").performScrollTo().performClick()
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
        compose.onNodeWithText("Show reminders").performClick()
        compose.onNodeWithText("Dismiss alarm").performScrollTo().performClick()
        compose.onNodeWithText("Open alarm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(occurrence), dismissed) }
    }

    @Test fun backgroundDoesNotPresentDialogButResumeDoes() {
        val foreground = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                ForegroundAlarmHost(listOf(alarm), foreground.value, false, {}) { }
            }
        }
        compose.onNodeWithText("Dismiss").assertDoesNotExist()
        compose.runOnIdle { foreground.value = true }
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
        compose.runOnIdle { foreground.value = false }
        compose.onNodeWithText("Dismiss").assertDoesNotExist()
    }

    @Test fun externallyDismissedAlarmDisappearsFromForeground() {
        val state = mutableStateOf(listOf(alarm))
        compose.setContent {
            MaterialTheme {
                ForegroundAlarmHost(state.value, true, false, {}) { }
            }
        }
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
        compose.runOnIdle { state.value = emptyList() }
        compose.onNodeWithText("Dismiss").assertDoesNotExist()
    }

    @Test fun listDisplaysRegisteredNextDoseRatherThanOriginalStart() {
        val next = occurrence.copy(test = false, scheduledTime = occurrence.scheduledTime + 8 * 3_600_000L)
        val state = mutableStateOf(AlarmUiState(pendingDoses = listOf(next)))
        compose.setContent {
            MaterialTheme {
                ReminderListScreen(reminders = listOf(reminder), alarmUiState = state.value)
            }
        }
        val expected = "Next scheduled dose: " + java.text.DateFormat.getDateTimeInstance()
            .format(java.util.Date(next.scheduledTime))
        compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = AlarmUiState() }
        compose.onNodeWithText("No dose currently scheduled").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(expected).assertDoesNotExist()
    }

    @Test fun testControlsDistinguishPendingAndActive() {
        DeveloperSettings.setEnabled(org.robolectric.RuntimeEnvironment.getApplication(), true)
        val state = mutableStateOf(AlarmUiState(pendingTests = listOf(occurrence)))
        var testTaps = 0
        var escalationTaps = 0
        compose.setContent {
            MaterialTheme {
                ReminderListScreen(reminders = listOf(reminder), alarmUiState = state.value,
                    onTest = { testTaps++ }, onTestEscalation = { escalationTaps++ })
            }
        }
        compose.onNodeWithText("Test pending:", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel pending test").assertExists()
        compose.onNodeWithText("Reschedule test in 1 minute").performScrollTo().performClick()
        compose.onNodeWithText("Test Escalation").performScrollTo().performClick()
        compose.runOnIdle { state.value = AlarmUiState(activeAlarms = listOf(alarm)) }
        compose.onNodeWithText("Test active — dismiss first").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Cancel pending test").assertDoesNotExist()
        compose.onNodeWithText("Test Escalation").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, testTaps)
            assertEquals(1, escalationTaps)
        }
    }
}
