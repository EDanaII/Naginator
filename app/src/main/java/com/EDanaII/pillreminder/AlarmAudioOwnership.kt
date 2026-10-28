package com.EDanaII.pillreminder

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Process-local burst ledger. Notification timeout remains effective if the process dies. */
internal object AlarmAudioOwnership {
    enum class Owner { APPLICATION, NOTIFICATION, STOPPED }
    private data class Burst(val request: AlarmPlaybackRequest, var owner: Owner = Owner.APPLICATION,
        var deadline: Long = 0, var timeout: Runnable? = null)
    private val bursts = mutableMapOf<String, Burst>()
    private val handler = Handler(Looper.getMainLooper())

    @Synchronized fun register(context: Context, request: AlarmPlaybackRequest): Boolean {
        val old = bursts[request.token]
        if (old != null && old.request.alertAt >= request.alertAt)
            return old.request.alertAt == request.alertAt && old.owner == Owner.APPLICATION
        old?.let { stop(context, it.request, "REPLACED") }
        bursts[request.token] = Burst(request)
        return true
    }

    @Synchronized fun application(context: Context, request: AlarmPlaybackRequest): Boolean {
        if (!register(context, request)) return false
        val burst = bursts.getValue(request.token)
        startDeadline(context, burst)
        event(context, "AUDIO_OWNER owner=APPLICATION atMs=${request.alertAt}")
        return true
    }

    @Synchronized fun isApplication(request: AlarmPlaybackRequest): Boolean =
        bursts[request.token]?.let { it.request.alertAt == request.alertAt && it.owner == Owner.APPLICATION } == true

    @Synchronized fun fallback(context: Context, request: AlarmPlaybackRequest, serviceUnavailable: Boolean = false) {
        val burst = bursts[request.token] ?: return
        if (burst.request.alertAt != request.alertAt || burst.owner != Owner.APPLICATION) return
        startDeadline(context, burst)
        val remaining = burst.deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) { stop(context, request, "TIMEOUT"); return }
        burst.owner = Owner.NOTIFICATION // Irreversible for this burst, including a failed submission.
        event(context, "AUDIO_OWNER owner=NOTIFICATION atMs=${request.alertAt}")
        AlarmNotificationAudio.show(context, request, remaining, serviceUnavailable)
    }

    @Synchronized fun failedStarts(context: Context, token: String? = null, at: Long? = null) {
        bursts.values.toList().filter { (token == null || it.request.token == token) &&
            (at == null || it.request.alertAt == at) }.forEach { fallback(context, it.request, true) }
    }

    private fun startDeadline(context: Context, burst: Burst) {
        if (burst.deadline != 0L) return
        burst.deadline = SystemClock.elapsedRealtime() + AlertEscalation.BURST_MS
        val app = context.applicationContext
        burst.timeout = Runnable { stop(app, burst.request, "TIMEOUT") }.also {
            handler.postDelayed(it, AlertEscalation.BURST_MS)
        }
    }

    @Synchronized fun stop(context: Context, request: AlarmPlaybackRequest, reason: String) {
        val burst = bursts[request.token] ?: return
        if (burst.request.alertAt != request.alertAt || burst.owner == Owner.STOPPED) return
        if (burst.owner == Owner.NOTIFICATION) AlarmNotificationAudio.cancel(context, request, reason)
        burst.owner = Owner.STOPPED
        burst.timeout?.let { handler.removeCallbacks(it) }
        event(context, "AUDIO_OWNER owner=STOPPED atMs=${request.alertAt}")
    }

    @Synchronized fun cancel(context: Context, id: Long, test: Boolean, token: String?) {
        bursts.values.toList().filter { it.request.occurrence.reminderId == id &&
            it.request.occurrence.test == test && (token == null || it.request.token == token) }
            .forEach { stop(context, it.request, "DISMISSED") }
        // Also cancel notifications retained by Android after process recreation.
        AlarmNotificationAudio.cancelOccurrence(context, id, test, token)
    }

    private fun event(context: Context, message: String) = AlarmSoundDiagnostics.event(context, message)

    @Synchronized internal fun resetForTests() {
        bursts.values.forEach { it.timeout?.let(handler::removeCallbacks) }
        bursts.clear()
    }
}
