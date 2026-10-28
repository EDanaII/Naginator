package com.EDanaII.pillreminder

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AlarmAlertSettingsTest {
    @Before fun enableDiagnostics() {
        DeveloperSettings.setEnabled(org.robolectric.RuntimeEnvironment.getApplication(), true)
        DeveloperSettings.setDetailed(org.robolectric.RuntimeEnvironment.getApplication(), true)
    }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pickerUsesAlarmTonesAndReturnsSelectionAndSwitchesRetainState() {
        var sound by mutableStateOf<String?>(null)
        var vibration by mutableStateOf(true)
        var escalation by mutableStateOf(true)
        compose.setContent {
            MaterialTheme { Column {
                AlarmAlertSettings(sound, vibration, escalation, true, { sound = it },
                    { vibration = it }, { escalation = it })
            } }
        }
        compose.onNodeWithText("Alarm sound: System alarm sound").performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(RingtoneManager.ACTION_RINGTONE_PICKER, request.intent.action)
        assertEquals(RingtoneManager.TYPE_ALARM, request.intent.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1))
        assertFalse(request.intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true))
        val selected = Uri.parse("content://media/external/audio/media/9")
        shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK,
            Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, selected))
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(selected.toString(), sound) }
        val logs = ShadowLog.getLogsForTag(AlarmSoundDiagnostics.TAG).joinToString { it.msg }
        assertTrue(logs.contains("PICKER_RESULT selection=CUSTOM readGrant=false persistableGrant=false"))
        assertFalse(logs.contains(selected.toString()))
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onAllNodes(isToggleable())[1].performClick()
        compose.runOnIdle { assertFalse(vibration); assertFalse(escalation) }
    }

    @Test fun defaultAndNullPickerResultsAreLoggedWithoutChangingNormalization() {
        var sound by mutableStateOf<String?>(null)
        compose.setContent {
            MaterialTheme { Column {
                AlarmAlertSettings(sound, true, true, true, { sound = it }, {}, {})
            } }
        }
        for (selected in listOf(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), null)) {
            compose.onNodeWithText("Alarm sound: System alarm sound").performClick()
            val request = shadowOf(compose.activity).nextStartedActivityForResult
            shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK,
                Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, selected))
            compose.waitForIdle()
            compose.runOnIdle { assertNull(sound) }
        }
        val logs = ShadowLog.getLogsForTag(AlarmSoundDiagnostics.TAG).joinToString { it.msg }
        assertTrue(logs.contains("PICKER_RESULT selection=DEFAULT"))
        assertTrue(logs.contains("PICKER_RESULT selection=NULL"))
    }
}
