package com.EDanaII.pillreminder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Looper
import android.os.SystemClock
import android.os.Vibrator
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
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [AudioFocusInstrumentationTest.CountingAudio::class])
class AudioFocusInstrumentationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val audio get() = shadowOf(context.getSystemService(AudioManager::class.java))
    private fun request() = AlarmPlaybackRequest(AlarmOccurrence(7, 100, "private-token", false, "r"),
        100, 0, "content://private/tone", true, true, true)

    @Before fun setUp() {
        CountingAudio.requests = 0
        AlarmAudioOwnership.resetForTests()
        DeveloperSettings.setEnabled(context, true)
        DeveloperSettings.setDetailed(context, true)
    }

    @Test fun denialRecordsContextAndTimingWithoutRetryingOrStartingAudio() {
        main.idleFor(Duration.ofSeconds(1)) // Keep the synthetic promotion timestamp non-negative.
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val vibrator = shadowOf(context.getSystemService(Vibrator::class.java))
        vibrator.setHasVibrator(true)
        var players = 0
        val output = AndroidOutput(context, LoopingAlarmSound(context, { _, _ -> AutoCloseable {} }) {
            players++; MediaPlayer()
        }) { FocusServiceSnapshot("PROMOTED", SystemClock.elapsedRealtime() - 125, 2, 3) }
        output.start(request())
        assertTrue(vibrator.isVibrating)
        val entry = AudioDiagnosticBuffer.export(context).lines().single { it.contains("FOCUS_REQUEST result=0") }
        for (field in listOf("attempt=1", "id=7 test=false", "serviceState=PROMOTED startId=3 fgsType=2",
            "sincePromotionMs=125", "importance=", "pid=", "uid=", "sdk=28", "targetSdk=37",
            "interactive=", "keyguardLocked=", "credentialUnlocked=", "burstAgeMs=", "callMs=")) {
            assertTrue("Missing diagnostic field: $field", entry.contains(field))
        }
        assertFalse(entry.contains("private-token"))
        assertFalse(entry.contains("content://"))
        val focusRequest = CountingAudio.request!!
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, focusRequest.focusGain)
        assertEquals(AudioAttributes.USAGE_ALARM, focusRequest.audioAttributes.usage)
        assertTrue(focusRequest.acceptsDelayedFocusGain())
        main.idleFor(Duration.ofSeconds(31))
        assertEquals(1, CountingAudio.requests)
        assertEquals(0, players)
        assertFalse(AudioDiagnosticBuffer.export(context).contains("PLAYBACK_BEGIN"))
        output.stop("TIMEOUT")
        val stopped = AudioDiagnosticBuffer.export(context).lines().single { it.contains("PLAYBACK_STOP") }
        assertEquals(entry.substringAfter("run=").substringBefore(' '), stopped.substringAfter("run="))
    }

    @Test fun unavailableDiagnosticStateCannotPreventGrantedPlayback() {
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, default), ShadowMediaPlayer.MediaInfo(60_000, 0))
        lateinit var player: MediaPlayer
        val output = AndroidOutput(context, LoopingAlarmSound(context, { _, _ -> AutoCloseable {} }) {
            MediaPlayer().also { player = it }
        }) { throw SecurityException("private diagnostic error") }
        output.start(request().copy(soundUri = null))
        main.idle()
        assertTrue(player.isPlaying)
        assertEquals(1, CountingAudio.requests)
        val exported = AudioDiagnosticBuffer.export(context)
        assertTrue(exported.contains("FOCUS_REQUEST result=1"))
        assertTrue(exported.contains("serviceState=UNKNOWN startId=-1 fgsType=-1 sincePromotionMs=-1"))
        assertFalse(exported.contains("private diagnostic error"))
        output.stop()
    }

    @Test fun extendedContextRejectsPrivateOrUnexpectedFields() {
        AudioDiagnosticBuffer.record(context, "FOCUS_REQUEST result=0 serviceState=PROMOTED startId=3 fgsType=2 " +
            "importance=-1 pid=1 uid=2 sdk=34 targetSdk=37 attempt=1 requestAtMs=123 callMs=1 burstAgeMs=0 " +
            "sincePromotionMs=-1 interactive=unknown keyguardLocked=true credentialUnlocked=false")
        AudioDiagnosticBuffer.record(context, "FOCUS_REQUEST result=0 serviceState=PrivateMedicine")
        AudioDiagnosticBuffer.record(context, "FOCUS_REQUEST result=0 interactive=PrivateMedicine")
        AudioDiagnosticBuffer.record(context, "FOCUS_REQUEST result=0 requestAtMs=content://private/tone")
        val exported = AudioDiagnosticBuffer.export(context)
        assertTrue(exported.contains("interactive=unknown keyguardLocked=true credentialUnlocked=false"))
        assertFalse(exported.contains("PrivateMedicine"))
        assertFalse(exported.contains("content://"))
    }

    @Implements(AudioManager::class)
    class CountingAudio : ShadowAudioManager() {
        @Implementation override fun requestAudioFocus(request: android.media.AudioFocusRequest): Int {
            requests++
            Companion.request = request
            return super.requestAudioFocus(request)
        }
        companion object { var requests = 0; var request: android.media.AudioFocusRequest? = null }
    }
}
