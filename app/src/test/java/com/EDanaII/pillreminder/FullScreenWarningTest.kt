package com.EDanaII.pillreminder

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [AlarmPermissionsTest.FullScreenNotificationManager::class])
class FullScreenWarningTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val warning = "Full-screen alarm access is off. Alarms may sound without waking the screen or displaying Dismiss."

    private fun show(allowed: Boolean, owner: LifecycleOwner = compose.activity) {
        AlarmPermissionsTest.FullScreenNotificationManager.allowed = allowed
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme { Column { AlarmPermissionsSection(compose.activity) } }
            }
        }
    }

    @Test fun deniedAccessShowsWarningAndSettingsWithoutExpandingPermissions() {
        show(false)
        compose.onNodeWithText(warning).assertIsDisplayed()
        compose.onNodeWithText("Alarm permissions").assertDoesNotExist()
        compose.onNodeWithText("Enable full-screen alerts").performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, intent.action)
        assertEquals("package:${compose.activity.packageName}", intent.dataString)
    }

    @Test fun grantedAccessHidesWarningAndAction() {
        show(true)
        compose.onNodeWithText(warning).assertDoesNotExist()
        compose.onNodeWithText("Enable full-screen alerts").assertDoesNotExist()
    }

    @Test fun resumeRefreshesBothGrantAndRevocationWithoutRecreatingScreen() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        show(false, owner)
        compose.onNodeWithText(warning).assertIsDisplayed()
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            AlarmPermissionsTest.FullScreenNotificationManager.allowed = true
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithText(warning).assertDoesNotExist()
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            AlarmPermissionsTest.FullScreenNotificationManager.allowed = false
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithText(warning).assertIsDisplayed()
    }
}
