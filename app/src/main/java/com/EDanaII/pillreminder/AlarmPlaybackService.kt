package com.EDanaII.pillreminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Started only for a delivered user-requested alarm, never directly by a boot receiver. */
class AlarmPlaybackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var controller: AlarmBurstController
    private lateinit var output: AndroidOutput
    private val preparations = mutableSetOf<Job>()
    private var destroyed = false
    private var latestStartId = 0
    private var diagnosticServiceState = "CREATED"
    private var promotedAtMs = -1L
    internal var preparePlayback: suspend (String, Long) -> AlarmPlaybackRequest? = { token, at ->
        AlarmCoordinator(applicationContext).playback(token, at)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        output = AndroidOutput(this, serviceSnapshot = {
            FocusServiceSnapshot(diagnosticServiceState, promotedAtMs,
                if (Build.VERSION.SDK_INT >= 29) try { foregroundServiceType } catch (_: RuntimeException) { -1 }
                else -1, latestStartId)
        })
        val presentingOutput = object : AlarmBurstController.Output {
            override fun start(request: AlarmPlaybackRequest) {
                if (!AlarmNotifications.canNotify(this@AlarmPlaybackService)) {
                    AlarmSoundDiagnostics.event(this@AlarmPlaybackService, "PLAYBACK_FAILURE reason=NOTIFICATIONS_BLOCKED")
                    return
                }
                // A waiting occurrence may have outlived its original notification timeout.
                // Refresh its own Dismiss action when its serialized audio burst actually starts.
                if (request.reminder != null && System.currentTimeMillis() - request.alertAt > 1_000) {
                    try {
                        AlarmNotifications.show(this@AlarmPlaybackService, request.reminder, request.occurrence)
                    } catch (exception: SecurityException) {
                        Log.w("MedicationAlarm", "Queued notification refresh denied", exception)
                        AlarmSoundDiagnostics.event(this@AlarmPlaybackService, "PLAYBACK_FAILURE reason=NOTIFICATIONS_BLOCKED")
                        return
                    }
                }
                output.start(request)
            }
            override fun stop() = output.stop()
            override fun stop(reason: String) = output.stop(reason)
        }
        controller = AlarmBurstController(presentingOutput, { delay, action ->
            val task = Runnable { action() }
            handler.postDelayed(task, delay)
            val cancel: () -> Unit = { handler.removeCallbacks(task) }
            cancel
        }, { stopIfIdle() })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (destroyed) return START_NOT_STICKY
        latestStartId = startId
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("alarm_playback", "Alarm playback service",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Required while alarm sound or vibration is active. This status notification is silent."
                setSound(null, null); enableVibration(false)
            })
            startForeground(99, Notification.Builder(this, "alarm_playback")
                .withNaggyIcons(this).setContentTitle("Medication alert")
                .setContentText("Alarm sound and vibration stop after 30 seconds.").setOngoing(true).build())
            promotedAtMs = SystemClock.elapsedRealtime()
            diagnosticServiceState = "PROMOTED"
        } catch (exception: RuntimeException) {
            diagnosticServiceState = "PROMOTION_FAILED"
            AlarmSoundDiagnostics.event(this, "SERVICE_START status=FAILED")
            Log.w("MedicationAlarm", "Foreground promotion failed; medication notification remains available", exception)
            // stopSelfResult may retain this instance for a newer queued start. Cancel only
            // this batch of work; the lifetime scope must stay usable until onDestroy.
            scope.coroutineContext.cancelChildren()
            controller.shutdown("FOREGROUND_FAILED")
            AlarmAudioOwnership.failedStarts(this)
            stopIfIdle()
            return START_NOT_STICKY
        }
        AlarmSoundDiagnostics.event(this, "SERVICE_START status=STARTED")
        val token = intent?.getStringExtra("token")
        if (token == null) {
            Log.w("MedicationAlarm", "Playback request suppressed: missing occurrence token")
            stopIfIdle()
            return START_NOT_STICKY
        }
        val at = intent.getLongExtra("alert_at", 0)
        val preparation = scope.launch {
            try {
                val request = withContext(Dispatchers.IO) { preparePlayback(token, at) }
                if (!destroyed && request != null) controller.enqueue(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                AlarmSoundDiagnostics.event(this@AlarmPlaybackService, "PLAYBACK_FAILURE reason=PREPARATION")
                output.failedStart(token, at)
                Log.w("MedicationAlarm", "Unable to prepare alarm playback", exception)
            }
        }
        preparations.add(preparation)
        // Completion runs even if cancellation precedes entry into the coroutine body.
        // Post back to main so the job ledger and service/controller lifecycle stay serialized.
        preparation.invokeOnCompletion {
            handler.post {
                preparations.remove(preparation)
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        if (!destroyed && preparations.isEmpty() && controller.current == null) {
            // Do not stop a newer alarm command already pending in Android's service dispatcher.
            if (stopSelfResult(latestStartId)) {
                diagnosticServiceState = "STOP_REQUESTED"
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        diagnosticServiceState = "DESTROYED"
        if (instance === this) instance = null
        scope.cancel()
        preparations.clear()
        controller.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile private var instance: AlarmPlaybackService? = null
        fun request(context: Context, token: String, at: Long): Boolean {
            return try {
                val component = context.startForegroundService(Intent(context, AlarmPlaybackService::class.java)
                    .putExtra("token", token).putExtra("alert_at", at))
                AlarmSoundDiagnostics.event(context, "SERVICE_REQUEST status=${if (component == null) "FAILED" else "ACCEPTED"}")
                component != null
            } catch (exception: RuntimeException) {
                AlarmSoundDiagnostics.event(context, "SERVICE_REQUEST status=FAILED")
                Log.w("MedicationAlarm", "Alarm playback start failed; notification remains available", exception)
                false
            }
        }
        fun dismiss(token: String) {
            val service = instance ?: return
            service.handler.post { if (!service.destroyed) service.controller.dismiss(token) }
        }
        fun dismissReminder(id: Long, test: Boolean? = null) {
            val service = instance ?: return
            service.handler.post { if (!service.destroyed) service.controller.dismissSchedule(id, test) }
        }
        fun dismissTokens(tokens: List<String>) {
            val service = instance ?: return
            service.handler.post { if (!service.destroyed) service.controller.dismissTokens(tokens) }
        }
    }
}

internal class AndroidOutput(private val context: Context,
    private val sound: LoopingAlarmSound = LoopingAlarmSound(context),
    private val serviceSnapshot: () -> FocusServiceSnapshot = { FocusServiceSnapshot() }) : AlarmBurstController.Output {
    private var request: AlarmPlaybackRequest? = null
    private var deadline = 0L
    private var resumeOnGain = false
    private var focusGeneration = 0
    private var focusRun: String? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val wake = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Naginator:alarm-burst").apply { setReferenceCounted(false) }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private var focus: AudioFocusRequest? = null

    private fun focusChanged(change: Int, expectedGeneration: Int) {
        if (request == null || focusGeneration != expectedGeneration) return
        if (!AlarmAudioOwnership.isApplication(request!!)) return
        AlarmSoundDiagnostics.event(context, "FOCUS_CHANGE change=$change run=$focusRun")
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnGain) {
                resumeOnGain = false
                playRemaining()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnGain = true
                sound.stop()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnGain = false
                sound.stop()
                // Permanent loss ends audio, but configured vibration remains bounded and active.
            }
        }
    }

    private fun playRemaining() {
        val current = request ?: return
        if (!AlarmAudioOwnership.isApplication(current)) return
        val remaining = deadline - SystemClock.elapsedRealtime()
        val generation = focusGeneration
        if (remaining > 0) try {
            sound.play(current.soundUri,
                AlertEscalation.level(current.level, current.escalation).gain, current.unlocked, remaining) {
                if (request === current && focusGeneration == generation) notificationFallback()
            }
        } catch (_: RuntimeException) { notificationFallback() }
    }

    private fun notificationFallback() {
        val current = request ?: return
        if (!AlarmAudioOwnership.isApplication(current)) return
        // Release application output before asking Android to sound a notification.
        resumeOnGain = false
        focusGeneration++
        sound.stop()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        AlarmAudioOwnership.fallback(context, current)
    }

    fun failedStart(token: String, at: Long) {
        if (request?.let { it.token == token && it.alertAt == at } == true) notificationFallback()
        else AlarmAudioOwnership.failedStarts(context, token, at)
    }

    override fun start(request: AlarmPlaybackRequest) {
        stop()
        if (!AlarmAudioOwnership.application(context, request)) return
        this.request = request
        focusRun = AlarmSoundDiagnostics.nextRun()
        deadline = SystemClock.elapsedRealtime() + AlertEscalation.BURST_MS
        wake.acquire(AlertEscalation.BURST_MS + 1_000)
        val expectedGeneration = focusGeneration
        val nextFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener({ change -> focusChanged(change, expectedGeneration) }, Handler(Looper.getMainLooper()))
            .build()
        focus = nextFocus
        val diagnosticContext = AudioFocusDiagnostics.capture(context, serviceSnapshot)
        val requestAtMs = SystemClock.elapsedRealtime()
        try {
            val result = audio.requestAudioFocus(nextFocus)
            AlarmSoundDiagnostics.event(context, "FOCUS_REQUEST result=$result run=$focusRun " +
                "id=${request.occurrence.reminderId} test=${request.occurrence.test} " +
                diagnosticContext(requestAtMs, deadline - AlertEscalation.BURST_MS))
            resumeOnGain = result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) playRemaining()
            else if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) notificationFallback()
            else if (result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
                // Delayed acceptance is not a guarantee that a gain callback will arrive.
                Handler(Looper.getMainLooper()).postDelayed({
                    if (this.request === request && focusGeneration == expectedGeneration && resumeOnGain)
                        notificationFallback()
                }, 10_000L)
            }
        } catch (exception: RuntimeException) {
            AlarmSoundDiagnostics.event(context, "FOCUS_REQUEST status=FAILED run=$focusRun " +
                diagnosticContext(requestAtMs, deadline - AlertEscalation.BURST_MS))
            Log.w("MedicationAlarm", "Audio focus request failed; configured vibration remains eligible", exception)
            notificationFallback()
        }
        if (request.vibration && vibrator?.hasVibrator() == true) {
            // A finite hardware waveform also stops if Doze suspends our cleanup callback.
            try {
                vibrator.vibrate(VibrationEffect.createWaveform(
                    AlertEscalation.finiteVibration(request.level, request.escalation), -1), attributes)
            } catch (_: RuntimeException) {
                AlarmSoundDiagnostics.event(context, "PLAYBACK_FAILURE reason=VIBRATION")
            }
        }
    }

    override fun stop() = stop("REPLACED")

    override fun stop(reason: String) {
        if (reason != "FOREGROUND_FAILED") request?.let { AlarmAudioOwnership.stop(context, it, reason) }
        if (request != null) AlarmSoundDiagnostics.event(context, "PLAYBACK_STOP reason=$reason run=$focusRun")
        focusRun = null
        request = null
        focusGeneration++
        resumeOnGain = false
        sound.stop()
        vibrator?.cancel()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        if (wake.isHeld) wake.release()
    }
}
