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
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Started only for a delivered user-requested alarm, never directly by a boot receiver. */
class AlarmPlaybackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var controller: AlarmBurstController
    private lateinit var output: AndroidOutput
    private var loads = 0
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        output = AndroidOutput(this)
        val presentingOutput = object : AlarmBurstController.Output {
            override fun start(request: AlarmPlaybackRequest) {
                // A waiting occurrence may have outlived its original notification timeout.
                // Refresh its own Dismiss action when its serialized audio burst actually starts.
                if (request.reminder != null && System.currentTimeMillis() - request.alertAt > 1_000) {
                    AlarmNotifications.show(this@AlarmPlaybackService, request.reminder, request.occurrence)
                }
                output.start(request)
            }
            override fun stop() = output.stop()
        }
        controller = AlarmBurstController(presentingOutput, { delay, action ->
            val task = Runnable { action() }
            handler.postDelayed(task, delay)
            val cancel: () -> Unit = { handler.removeCallbacks(task) }
            cancel
        }, { stopIfIdle() })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("alarm_playback", "Alarm playback",
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        startForeground(99, Notification.Builder(this, "alarm_playback")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Medication alert")
            .setContentText("Alarm sound and vibration stop after 30 seconds.").setOngoing(true).build())
        val token = intent?.getStringExtra("token")
        if (token == null) {
            Log.w("MedicationAlarm", "Playback request suppressed: missing occurrence token")
            stopIfIdle()
            return START_NOT_STICKY
        }
        val at = intent.getLongExtra("alert_at", 0)
        loads++
        scope.launch {
            try {
                val request = withContext(Dispatchers.IO) { AlarmCoordinator(applicationContext).playback(token, at) }
                if (request != null) controller.enqueue(request)
            } catch (exception: Exception) {
                Log.w("MedicationAlarm", "Unable to prepare alarm playback", exception)
            } finally {
                loads--
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        if (loads == 0 && controller.current == null) {
            // Do not stop a newer alarm command already pending in Android's service dispatcher.
            if (stopSelfResult(latestStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        controller.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile private var instance: AlarmPlaybackService? = null
        fun request(context: Context, token: String, at: Long) {
            try {
                context.startForegroundService(Intent(context, AlarmPlaybackService::class.java)
                    .putExtra("token", token).putExtra("alert_at", at))
            } catch (exception: IllegalStateException) {
                Log.w("MedicationAlarm", "System deferred alarm playback; notification remains available", exception)
            } catch (exception: SecurityException) {
                Log.w("MedicationAlarm", "Alarm playback denied; notification remains available", exception)
            }
        }
        fun dismiss(token: String) {
            val service = instance ?: return
            service.handler.post { service.controller.dismiss(token) }
        }
        fun dismissReminder(id: Long, test: Boolean? = null) {
            val service = instance ?: return
            service.handler.post { service.controller.dismissSchedule(id, test) }
        }
        fun dismissTokens(tokens: List<String>) {
            val service = instance ?: return
            service.handler.post { service.controller.dismissTokens(tokens) }
        }
    }
}

internal class AndroidOutput(context: Context) : AlarmBurstController.Output {
    private val sound = LoopingAlarmSound(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val wake = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Naginator:alarm-burst").apply { setReferenceCounted(false) }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }.build()

    override fun start(request: AlarmPlaybackRequest) {
        stop()
        wake.acquire(AlertEscalation.BURST_MS + 1_000)
        val level = AlertEscalation.level(request.level, request.escalation)
        if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            sound.play(request.soundUri, level.gain, request.unlocked)
        } else {
            Log.w("MedicationAlarm", "Audio suppressed token=${request.token}: audio focus denied; " +
                "configured vibration remains eligible")
        }
        if (request.vibration && vibrator?.hasVibrator() == true) {
            // A finite hardware waveform also stops if Doze suspends our cleanup callback.
            vibrator.vibrate(VibrationEffect.createWaveform(
                AlertEscalation.finiteVibration(request.level, request.escalation), -1), attributes)
        }
    }

    override fun stop() {
        sound.stop()
        vibrator?.cancel()
        audio.abandonAudioFocusRequest(focus)
        if (wake.isHeld) wake.release()
    }
}
