package com.EDanaII.pillreminder

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
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
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.util.DataSource
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AlarmSoundTest {
    @Before fun resetOwnership() {
        AlarmAudioOwnership.resetForTests()
        DeveloperSettings.setEnabled(context, true)
        DeveloperSettings.setDetailed(context, true)
    }
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun diagnostics() = ShadowLog.getLogsForTag(AlarmSoundDiagnostics.TAG).joinToString("\n") { it.msg }

    @Test fun customSuccessLogsActualSourceAndAllGainsWithoutPrivateData() {
        val selected = Uri.parse("content://private-provider/private-medication-tone.mp3")
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, selected), ShadowMediaPlayer.MediaInfo())
        lateinit var player: MediaPlayer
        val sound = LoopingAlarmSound(context, { _, _ -> AutoCloseable {} }) {
            MediaPlayer().also { player = it }
        }
        for (gain in listOf(.25f, .5f, .75f, 1f)) {
            sound.play(selected.toString(), gain, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(DataSource.toDataSource(context, selected), shadowOf(player).dataSource)
            assertEquals(gain, shadowOf(player).leftVolume)
            assertTrue(player.isPlaying)
            assertTrue(diagnostics().contains("source=CUSTOM gain=$gain"))
            assertTrue(diagnostics().contains("CANDIDATE_STARTED"))
            sound.stop()
        }
        assertFalse(diagnostics().contains("private-provider"))
        assertFalse(diagnostics().contains("private-medication-tone"))
        assertFalse(diagnostics().contains("source=SYSTEM_DEFAULT"))
    }

    @Test fun permissionFailureLogsStageAndTypeButRedactsExceptionAndCauseText() {
        val selected = Uri.parse("content://private-provider/private-tone.mp3")
        ShadowMediaPlayer.addException(DataSource.toDataSource(context, selected),
            SecurityException("Denied $selected", IOException("private-tone.mp3")))
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, default), ShadowMediaPlayer.MediaInfo())
        val sound = LoopingAlarmSound(context, { _, _ -> AutoCloseable {} })
        sound.play(selected.toString(), .5f, true)
        shadowOf(Looper.getMainLooper()).idle()
        val logs = diagnostics()
        assertTrue(logs.contains("source=CUSTOM stage=SET_DATA_SOURCE gain=0.5 exception=java.lang.SecurityException"))
        assertTrue(logs.contains("causeType=java.io.IOException"))
        assertTrue(logs.contains("source=SYSTEM_DEFAULT gain=0.5"))
        assertFalse(logs.contains("private-provider"))
        assertFalse(logs.contains("private-tone"))
        assertTrue(ShadowLog.getLogsForTag(AlarmSoundDiagnostics.TAG).all { it.throwable == null })
        sound.stop()
    }

    @Test fun asynchronousMediaErrorLogsCodesBeforeDefaultFallback() {
        val selected = Uri.parse("content://private/tone")
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, selected), ShadowMediaPlayer.MediaInfo(1000, 5000))
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, default), ShadowMediaPlayer.MediaInfo())
        val players = mutableListOf<MediaPlayer>()
        val sound = LoopingAlarmSound(context, { _, _ -> AutoCloseable {} }) {
            MediaPlayer().also { players.add(it) }
        }
        sound.play(selected.toString(), .75f, true)
        shadowOf(players.first()).invokeErrorListener(1, -1004)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(diagnostics().contains("source=CUSTOM stage=PREPARE_ASYNC mediaErrorWhat=1 mediaErrorExtra=-1004 gain=0.75"))
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.first()).state)
        assertTrue(players.last().isPlaying)
        assertEquals(.75f, shadowOf(players.last()).leftVolume)
        sound.stop()
    }

    @Test fun cutoffFailureIsDistinguishedFromContentFailure() {
        val selected = Uri.parse("content://private/tone")
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, selected), ShadowMediaPlayer.MediaInfo())
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, default), ShadowMediaPlayer.MediaInfo())
        var attempts = 0
        val sound = LoopingAlarmSound(context, { _, _ ->
            if (attempts++ == 0) throw IllegalStateException("private-tone.mp3")
            AutoCloseable {}
        })
        sound.play(selected.toString(), .25f, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(diagnostics().contains("source=CUSTOM stage=NATIVE_CUTOFF gain=0.25 exception=java.lang.IllegalStateException"))
        assertTrue(diagnostics().contains("CANDIDATE_STARTED"))
        assertFalse(diagnostics().contains("private-tone.mp3"))
        sound.stop()
    }

    @Test fun unavailableSelectedToneFallsBackToDefaultAndLoopsAtRequestedGain() {
        val selected = Uri.parse("content://missing/alarm")
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ShadowMediaPlayer.addException(DataSource.toDataSource(context, selected), IOException("Removed tone"))
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, default), ShadowMediaPlayer.MediaInfo())
        val players = mutableListOf<MediaPlayer>()
        val cutoffs = mutableListOf<Long>()
        val sound = LoopingAlarmSound(context, { _, duration -> cutoffs.add(duration); AutoCloseable {} }) {
            MediaPlayer().also { players.add(it) }
        }
        sound.play(selected.toString(), .5f, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, players.size)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.first()).state)
        val player = players.last()
        assertEquals(DataSource.toDataSource(context, default), shadowOf(player).dataSource)
        assertTrue(player.isPlaying)
        assertTrue(player.isLooping)
        assertEquals(.5f, shadowOf(player).leftVolume)
        assertEquals(.5f, shadowOf(player).rightVolume)
        assertEquals(listOf(30_000L), cutoffs)
        sound.stop()
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
    }

    @Test fun lockedPlaybackSkipsPrivateUriAndFallsBackToPackagedSound() {
        val choices = AlarmSounds.candidates(context, "content://private/tone-name", false)
        assertEquals(2, choices.size)
        assertTrue(choices.none { it.toString().contains("private") })
        ShadowMediaPlayer.addException(DataSource.toDataSource(context, choices[0]), IOException("Locked provider"))
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, choices[1]), ShadowMediaPlayer.MediaInfo())
        val players = mutableListOf<MediaPlayer>()
        val sound = LoopingAlarmSound(context, { _, _ -> AutoCloseable {} }) { MediaPlayer().also { players.add(it) } }
        sound.play("content://private/tone-name", 1f, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(DataSource.toDataSource(context, choices[1]), shadowOf(players.last()).dataSource)
        assertTrue(players.last().isPlaying)
        assertTrue(diagnostics().contains("source=PACKAGED_FALLBACK gain=1.0"))
        assertFalse(diagnostics().contains("source=CUSTOM"))
        assertTrue(diagnostics().contains("unlocked=false"))
        assertTrue(context.resources.openRawResource(R.raw.naginator_alarm).use { it.readBytes().size > 32 })
        sound.stop()
    }

    @Test fun cancellationDuringPreparationCannotStartAudioLater() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri), ShadowMediaPlayer.MediaInfo(1000, 500))
        lateinit var player: MediaPlayer
        val sound = LoopingAlarmSound(context, { _, _ -> AutoCloseable {} }) { MediaPlayer().also { player = it } }
        sound.play(null, .25f, true)
        sound.stop()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
    }

    @Test fun vibrationPatternsRepeatAndStopAndCanBeDisabled() {
        val vibrator = shadowOf(context.getSystemService(Vibrator::class.java))
        vibrator.setHasVibrator(true)
        val output = AndroidOutput(context)
        val request = AlarmPlaybackRequest(AlarmOccurrence(1, 100, "token", false, "r"),
            100, 2, null, false, true, true)
        output.start(request)
        assertFalse(vibrator.isVibrating)
        output.start(request.copy(vibration = true, alertAt = 101))
        assertTrue(vibrator.isVibrating)
        assertArrayEquals(AlertEscalation.finiteVibration(2), vibrator.pattern)
        assertEquals(30_000L, vibrator.pattern.sum())
        assertEquals(-1, vibrator.repeat)
        output.stop()
        assertFalse(vibrator.isVibrating)
        assertTrue(vibrator.isCancelled)
        vibrator.setHasVibrator(false)
        output.start(request.copy(vibration = true, alertAt = 102))
        assertFalse(vibrator.isVibrating)
        output.stop()
    }

    @Test fun nativeCutoffIsStepToSilenceAndNeverExceedsThirtySeconds() {
        val config = NativeAlarmCutoff.configuration(60_000)
        assertEquals(30_000L, config.duration)
        assertEquals(android.media.VolumeShaper.Configuration.INTERPOLATOR_TYPE_STEP, config.interpolatorType)
        assertArrayEquals(floatArrayOf(1f, 0f), config.volumes, .0001f)
        for (level in 0..3) {
            assertEquals(30_000L, AlertEscalation.finiteVibration(level).sum())
        }
    }

    @Test fun slowPreparationReducesNativeCutoffInsteadOfExtendingBurst() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri), ShadowMediaPlayer.MediaInfo(1000, 5_000))
        val cutoffs = mutableListOf<Long>()
        val sound = LoopingAlarmSound(context, { _, duration -> cutoffs.add(duration); AutoCloseable {} })
        sound.play(null, 1f, true)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        assertEquals(listOf(25_000L), cutoffs)
        sound.stop()
    }
}
