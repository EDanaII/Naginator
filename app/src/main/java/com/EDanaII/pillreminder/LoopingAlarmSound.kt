package com.EDanaII.pillreminder

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.VolumeShaper
import android.net.Uri
import android.os.SystemClock
import android.os.Handler
import android.os.Looper

internal object AlarmSounds {
    fun candidates(context: Context, selected: String?, unlocked: Boolean): List<Uri> = buildList {
        if (unlocked && !selected.isNullOrBlank()) add(Uri.parse(selected))
        add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        // Packaged resource is accessible even when a selected/default content provider is locked.
        add(Uri.parse("android.resource://${context.packageName}/raw/naginator_alarm"))
    }.distinct()
}

/** Async preparation, looping, fallback, and cancellation all run on the service's main thread. */
internal object NativeAlarmCutoff {
    fun configuration(duration: Long): VolumeShaper.Configuration = VolumeShaper.Configuration.Builder()
        .setDuration(duration.coerceIn(1, AlertEscalation.BURST_MS))
        .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_STEP)
        .setCurve(floatArrayOf(0f, 1f), floatArrayOf(1f, 0f)).build()

    fun apply(player: MediaPlayer, duration: Long): AutoCloseable = player.createVolumeShaper(configuration(duration)).also {
        // The public Builder defaults to clock time, so native muting does not restart on loops.
        it.apply(VolumeShaper.Operation.PLAY)
    }
}

internal class LoopingAlarmSound(private val context: Context,
    private val createCutoff: (MediaPlayer, Long) -> AutoCloseable = NativeAlarmCutoff::apply,
    private val createPlayer: () -> MediaPlayer = { MediaPlayer() }) {
    private var player: MediaPlayer? = null
    private var cutoff: AutoCloseable? = null
    private var generation = 0
    private val handler = Handler(Looper.getMainLooper())
    private var preparationTimeout: Runnable? = null

    fun play(selected: String?, gain: Float, unlocked: Boolean, durationMs: Long = AlertEscalation.BURST_MS,
        exhausted: () -> Unit = {}) {
        stop()
        val epoch = generation
        val deadline = SystemClock.elapsedRealtime() + durationMs.coerceIn(0, AlertEscalation.BURST_MS)
        val candidates = AlarmSounds.candidates(context, selected, unlocked)
        val run = AlarmSoundDiagnostics.nextRun()
        AlarmSoundDiagnostics.event(context, "PLAYBACK_BEGIN run=$run selection=${AlarmSoundDiagnostics.selection(selected)} " +
            "unlocked=$unlocked gain=$gain")
        fun attempt(index: Int) {
            if (epoch != generation) return
            if (SystemClock.elapsedRealtime() >= deadline) return
            if (index >= candidates.size) {
                AlarmSoundDiagnostics.event(context, "PLAYBACK_EXHAUSTED run=$run gain=$gain")
                exhausted()
                return
            }
            val source = when (candidates[index]) {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) -> "SYSTEM_DEFAULT"
                Uri.parse("android.resource://${context.packageName}/raw/naginator_alarm") -> "PACKAGED_FALLBACK"
                else -> "CUSTOM"
            }
            AlarmSoundDiagnostics.event(context, "CANDIDATE_ATTEMPT run=$run source=$source gain=$gain")
            val next = try { createPlayer() } catch (exception: Exception) {
                AlarmSoundDiagnostics.failure(context, "CANDIDATE_FAILED run=$run source=$source stage=CREATE_PLAYER gain=$gain", exception)
                attempt(index + 1)
                return
            }
            player = next
            var stage = "AUDIO_ATTRIBUTES"
            fun fallback() {
                if (player !== next || epoch != generation) return
                cancelPreparationTimeout()
                player = null
                next.release()
                closeCutoff()
                attempt(index + 1)
            }
            try {
                next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                stage = "SET_DATA_SOURCE"
                next.setDataSource(context, candidates[index])
                stage = "SET_LOOPING"
                next.isLooping = true
                stage = "SET_GAIN"
                next.setVolume(gain, gain)
                stage = "SET_LISTENERS"
                next.setOnPreparedListener {
                    if (player === next && epoch == generation) {
                        cancelPreparationTimeout()
                        AlarmSoundDiagnostics.event(context, "CANDIDATE_PREPARED run=$run source=$source gain=$gain")
                        val remaining = deadline - SystemClock.elapsedRealtime()
                        if (remaining <= 0) {
                            AlarmSoundDiagnostics.event(context, "PLAYBACK_DEADLINE run=$run source=$source gain=$gain")
                            stop()
                        }
                        else try {
                            // Install the native guard before starting any audible loop.
                            stage = "NATIVE_CUTOFF"
                            cutoff = createCutoff(next, remaining)
                            stage = "START"
                            next.start()
                            stage = "PLAYING"
                            AlarmSoundDiagnostics.event(context, "CANDIDATE_STARTED run=$run source=$source gain=$gain")
                        } catch (exception: RuntimeException) {
                            AlarmSoundDiagnostics.failure(context, "CANDIDATE_FAILED run=$run source=$source stage=$stage gain=$gain", exception)
                            fallback()
                        }
                    }
                }
                next.setOnErrorListener { _, what, extra ->
                    if (player === next && epoch == generation) {
                        AlarmSoundDiagnostics.event(context, "CANDIDATE_FAILED run=$run source=$source stage=$stage " +
                            "mediaErrorWhat=$what mediaErrorExtra=$extra gain=$gain")
                    }
                    fallback()
                    true
                }
                stage = "PREPARE_ASYNC"
                preparationTimeout = Runnable {
                    if (player === next && epoch == generation) {
                        AlarmSoundDiagnostics.event(context, "CANDIDATE_TIMEOUT run=$run source=$source gain=$gain")
                        fallback()
                    }
                }.also { handler.postDelayed(it, minOf(6_000L, deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)) }
                next.prepareAsync()
            } catch (exception: Exception) {
                AlarmSoundDiagnostics.failure(context, "CANDIDATE_FAILED run=$run source=$source stage=$stage gain=$gain", exception)
                fallback()
            }
        }
        attempt(0)
    }

    fun stop() {
        generation++
        cancelPreparationTimeout()
        val previous = player
        player = null
        previous?.release()
        closeCutoff()
    }

    private fun cancelPreparationTimeout() {
        preparationTimeout?.let { handler.removeCallbacks(it) }
        preparationTimeout = null
    }

    private fun closeCutoff() {
        try { cutoff?.close() } catch (_: Exception) { /* The player may already be released. */ }
        cutoff = null
    }
}
