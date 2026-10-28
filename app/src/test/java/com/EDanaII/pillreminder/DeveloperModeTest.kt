package com.EDanaII.pillreminder

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeveloperModeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun aboutShowsBuildIdentityAndSevenTapsRevealDeveloperTools() {
        compose.setContent { MaterialTheme {
            ReminderListScreen(reminders = listOf(Reminder(id = 1, name = "Dose", instructions = "Details",
                startTimeMillis = 100, doseIntervalHours = 8, nagIntervalMinutes = 1)))
        } }
        val version = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
        compose.onNodeWithText("About Naginator").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Commit ${BuildConfig.GIT_REVISION}").assertExists()
        assertTrue(BuildConfig.VERSION_CODE > 1)
        assertTrue(BuildConfig.GIT_REVISION.matches(Regex("[0-9a-f]{7,40}(?:-dirty)?|unknown")))
        compose.onNodeWithText("Test in 1 minute").assertDoesNotExist()
        compose.onNodeWithText("Test Escalation").assertDoesNotExist()
        compose.onNodeWithText("Copy audio diagnostics").assertDoesNotExist()
        compose.onNodeWithText("Diagnostic: system alarm audio in 1 minute").assertDoesNotExist()
        repeat(6) { compose.onNodeWithText(version).performScrollTo().performClick() }
        assertFalse(DeveloperSettings.read(compose.activity).enabled)
        compose.onNodeWithText(version).performClick()
        compose.onNodeWithContentDescription("Developer Mode").assertIsOn()
        compose.onNodeWithContentDescription("Detailed diagnostics").assertIsOff()
        compose.onNodeWithText("Test in 1 minute").assertExists()
        compose.onNodeWithText("Test Escalation").assertExists()
        compose.onNodeWithText("Copy audio diagnostics").assertExists()
        compose.onNodeWithText("Diagnostic: system alarm audio in 1 minute").assertExists()
        compose.onNodeWithContentDescription("Detailed diagnostics").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Detailed diagnostics").assertIsOn()
        compose.onNodeWithContentDescription("Developer Mode").performScrollTo().performClick()
        compose.onNodeWithText("Copy audio diagnostics").assertDoesNotExist()
        compose.onNodeWithText("Test in 1 minute").assertDoesNotExist()
        assertEquals(DeveloperOptions(), DeveloperSettings.read(compose.activity))
    }

    @Test fun optionsPersistAcrossActivityRecreationAndDisablingResetsDetail() {
        DeveloperSettings.setEnabled(compose.activity, true)
        DeveloperSettings.setDetailed(compose.activity, true)
        compose.activityRule.scenario.recreate()
        assertEquals(DeveloperOptions(true, true), DeveloperSettings.read(compose.activity))
        DeveloperSettings.setEnabled(compose.activity, false)
        compose.activityRule.scenario.recreate()
        assertEquals(DeveloperOptions(), DeveloperSettings.read(compose.activity))
        DeveloperSettings.setEnabled(compose.activity, true)
        assertFalse(DeveloperSettings.read(compose.activity).detailed)
    }

    @Test fun defaultOptionsAreOffAndDetailsCannotEnableWithoutDeveloperMode() {
        assertEquals(DeveloperOptions(), DeveloperSettings.read(compose.activity))
        DeveloperSettings.setDetailed(compose.activity, true)
        assertEquals(DeveloperOptions(), DeveloperSettings.read(compose.activity))
    }
}
