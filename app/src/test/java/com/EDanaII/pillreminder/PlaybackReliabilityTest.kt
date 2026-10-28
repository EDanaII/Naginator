package com.EDanaII.pillreminder

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Looper
import android.os.Vibrator
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaybackReliabilityTest {
    @Before fun resetOwnership() { AlarmAudioOwnership.resetForTests() }
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private fun request() = AlarmPlaybackRequest(AlarmOccurrence(1, 100, "token", false, "r"),
        100, 2, null, true, true, true)

    @Test fun transientFocusLossKeepsVibrationAndResumesOnlyWithinOriginalDeadline() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri), ShadowMediaPlayer.MediaInfo(60_000, 0))
        val cutoffs = mutableListOf<Long>()
        val players = mutableListOf<MediaPlayer>()
        val sound = LoopingAlarmSound(context, { _, ms -> cutoffs.add(ms); AutoCloseable {} }) {
            MediaPlayer().also { players.add(it) }
        }
        val output = AndroidOutput(context, sound)
        val audio = shadowOf(context.getSystemService(AudioManager::class.java))
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val vibrator = shadowOf(context.getSystemService(Vibrator::class.java))
        vibrator.setHasVibrator(true)
        output.start(request())
        main.idle()
        val listener = audio.lastAudioFocusRequest.listener!!
        main.idleFor(Duration.ofSeconds(10))
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(vibrator.isVibrating)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.last()).state)
        main.idleFor(Duration.ofSeconds(5))
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        main.idle()
        assertEquals(listOf(30_000L, 15_000L), cutoffs)
        assertTrue(players.last().isPlaying)
        main.idleFor(Duration.ofSeconds(16))
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(2, players.size)
        output.stop("TIMEOUT")
        assertFalse(vibrator.isVibrating)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(2, players.size)
    }

    @Test fun delayedFocusAndAbandonedBurstCannotRestartAfterDismissalOrReplacement() {
        val audio = shadowOf(context.getSystemService(AudioManager::class.java))
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        val players = mutableListOf<MediaPlayer>()
        val output = AndroidOutput(context, LoopingAlarmSound(context, { _, _ -> AutoCloseable {} }) {
            MediaPlayer().also { players.add(it) }
        })
        output.start(request())
        val oldFocus = audio.lastAudioFocusRequest
        assertTrue(oldFocus.audioFocusRequest.acceptsDelayedFocusGain())
        assertTrue(players.isEmpty())
        output.stop("DISMISSED")
        output.start(request().copy(occurrence = request().occurrence.copy(token = "other")))
        oldFocus.listener!!.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(players.isEmpty())
        output.stop()
    }

    @Test fun delayedFocusGainStartsRemainingBurstAndPermanentLossDoesNotResume() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri), ShadowMediaPlayer.MediaInfo(60_000, 0))
        val audio = shadowOf(context.getSystemService(AudioManager::class.java))
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        val players = mutableListOf<MediaPlayer>()
        val cutoffs = mutableListOf<Long>()
        val output = AndroidOutput(context, LoopingAlarmSound(context, { _, ms -> cutoffs.add(ms); AutoCloseable {} }) {
            MediaPlayer().also { players.add(it) }
        })
        output.start(request())
        val listener = audio.lastAudioFocusRequest.listener!!
        main.idleFor(Duration.ofSeconds(5))
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        main.idle()
        assertEquals(listOf(25_000L), cutoffs)
        assertTrue(players.single().isPlaying)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, players.size)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.single()).state)
        output.stop()
    }

    @Test fun hungCustomPreparationFallsBackAndLateCallbackCannotResurrectIt() {
        val custom = android.net.Uri.parse("content://test/stalled")
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, custom), ShadowMediaPlayer.MediaInfo(60_000, 20_000))
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, default), ShadowMediaPlayer.MediaInfo(60_000, 0))
        val cutoffs = mutableListOf<Long>()
        val players = mutableListOf<MediaPlayer>()
        val sound = LoopingAlarmSound(context, { _, ms -> cutoffs.add(ms); AutoCloseable {} }) {
            MediaPlayer().also { players.add(it) }
        }
        sound.play(custom.toString(), .75f, true)
        main.idleFor(Duration.ofSeconds(6))
        assertEquals(2, players.size)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.first()).state)
        assertTrue(players.last().isPlaying)
        assertEquals(listOf(24_000L), cutoffs)
        sound.stop()
        main.idleFor(Duration.ofSeconds(20))
        assertEquals(2, players.size)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.last()).state)
        assertTrue(AudioDiagnosticBuffer.export(context).contains("CANDIDATE_TIMEOUT"))
    }

    @Test fun playerCreationFailureAdvancesToNextCandidate() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri), ShadowMediaPlayer.MediaInfo(60_000, 0))
        var attempts = 0
        lateinit var player: MediaPlayer
        val sound = LoopingAlarmSound(context, { _, _ -> AutoCloseable {} }) {
            if (++attempts == 1) throw IllegalStateException("Unavailable player")
            MediaPlayer().also { player = it }
        }
        sound.play("content://test/custom", 1f, true)
        main.idle()
        assertEquals(2, attempts)
        assertTrue(player.isPlaying)
        sound.stop()
    }

    @Test fun rejectedOrMissingServiceStartIsReportedWithoutStartingAnotherAudioSource() {
        for (failure in listOf<RuntimeException?>(SecurityException(), IllegalStateException(), null)) {
            val wrapper = object : ContextWrapper(context) {
                override fun startForegroundService(service: Intent): ComponentName? {
                    failure?.let { throw it }
                    return null
                }
            }
            assertFalse(AlarmPlaybackService.request(wrapper, "token", 100))
        }
        assertTrue(AudioDiagnosticBuffer.export(context).contains("SERVICE_REQUEST status=FAILED"))
    }
}
