package com.EDanaII.pillreminder

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiagnosticLoggingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun defaultLoggingKeepsFailureAndRecoveryButSuppressesVerboseEventsInStorageAndLogcat() {
        AlarmSoundDiagnostics.event(context, "PLAYBACK_BEGIN selection=CUSTOM unlocked=true gain=0.25")
        AlarmSoundDiagnostics.event(context, "FOCUS_REQUEST result=1 importance=100 requestAtMs=123")
        AlarmSoundDiagnostics.event(context, "FOCUS_REQUEST result=0 importance=125 requestAtMs=456 id=1 test=false")
        AlarmSoundDiagnostics.event(context, "AUDIO_OWNER owner=NOTIFICATION atMs=100")
        AlarmSoundDiagnostics.event(context, "FALLBACK_POST status=ACCEPTED atMs=100 importance=4 soundEnabled=true")
        AlarmSoundDiagnostics.event(context, "FALLBACK_CANCEL reason=DISMISSED atMs=100")
        val exported = AudioDiagnosticBuffer.export(context)
        assertTrue(exported.contains("FOCUS_REQUEST result=0 id=1 test=false"))
        assertTrue(exported.contains("AUDIO_OWNER owner=NOTIFICATION"))
        assertTrue(exported.contains("FALLBACK_POST status=ACCEPTED"))
        assertTrue(exported.contains("FALLBACK_CANCEL reason=DISMISSED"))
        val logcat = ShadowLog.getLogsForTag(AlarmSoundDiagnostics.TAG).joinToString { it.msg }
        for (text in listOf(exported, logcat)) {
            assertFalse(text.contains("PLAYBACK_BEGIN"))
            assertFalse(text.contains("result=1"))
            assertFalse(text.contains("requestAtMs"))
            assertFalse(text.contains("importance=125"))
        }
    }

    @Test fun disablingDetailsPreservesOldLogsAndBoundedEssentialEventsContinue() {
        DeveloperSettings.setEnabled(context, true)
        DeveloperSettings.setDetailed(context, true)
        AudioDiagnosticBuffer.record(context, "PLAYBACK_BEGIN selection=CUSTOM unlocked=true gain=0.25")
        DeveloperSettings.setDetailed(context, false)
        AudioDiagnosticBuffer.record(context, "CANDIDATE_PREPARED source=CUSTOM gain=0.25")
        assertTrue(AudioDiagnosticBuffer.export(context).contains("PLAYBACK_BEGIN"))
        assertFalse(AudioDiagnosticBuffer.export(context).contains("CANDIDATE_PREPARED"))
        repeat(205) { AudioDiagnosticBuffer.record(context, "PLAYBACK_FAILURE reason=PREPARATION id=$it") }
        assertEquals(200, AudioDiagnosticBuffer.export(context).lines().count { it.contains("PLAYBACK_FAILURE") })
    }

    @Test fun enabledDetailsRetainCurrentTraceAndRejectPrivateFields() {
        DeveloperSettings.setEnabled(context, true)
        DeveloperSettings.setDetailed(context, true)
        val message = "FOCUS_REQUEST result=1 importance=100 requestAtMs=123 serviceState=PROMOTED fgsType=2"
        AlarmSoundDiagnostics.event(context, message)
        AudioDiagnosticBuffer.record(context, "FOCUS_REQUEST result=0 uri=content://private/tone")
        val exported = AudioDiagnosticBuffer.export(context)
        assertTrue(exported.contains(message))
        assertFalse(exported.contains("content://"))
    }
}
