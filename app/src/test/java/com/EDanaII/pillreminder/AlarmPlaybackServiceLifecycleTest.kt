package com.EDanaII.pillreminder

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.os.Vibrator
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [AlarmPlaybackServiceLifecycleTest.ServiceControl::class])
class AlarmPlaybackServiceLifecycleTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var service: AlarmPlaybackService
    private var destroyed = false

    @Before fun setUp() {
        DeveloperSettings.setEnabled(context, true)
        DeveloperSettings.setDetailed(context, true)
        AlarmAudioOwnership.resetForTests()
        ServiceControl.failPromotion = false
        ServiceControl.systemLatestStart = 0
        ServiceControl.stopAttempts.clear()
        service = Robolectric.buildService(AlarmPlaybackService::class.java).create().get()
    }

    @After fun tearDown() {
        if (!destroyed) service.onDestroy()
        main.idle()
    }

    private fun intent(token: String) = Intent(context, AlarmPlaybackService::class.java)
        .putExtra("token", token).putExtra("alert_at", 100L)

    private fun playback(token: String) = AlarmPlaybackRequest(
        AlarmOccurrence(1, 100, token, false, "revision"), 100, 0, null, true, true, true)

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val pause = CountDownLatch(1)
        repeat(500) {
            main.idle()
            if (condition()) return
            pause.await(10, TimeUnit.MILLISECONDS)
        }
        fail(description)
    }

    @Test fun failedPromotionDoesNotPoisonSubsequentStartOnSameInstance() {
        val prepared = AtomicInteger()
        service.preparePlayback = { _, _ -> prepared.incrementAndGet(); null }
        // Model Android already having accepted a second command before the first is dispatched.
        ServiceControl.systemLatestStart = 2
        ServiceControl.failPromotion = true
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent("failed"), 0, 1))
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(0, prepared.get())

        ServiceControl.failPromotion = false
        service.onStartCommand(intent("next"), 0, 2)
        awaitCondition("The surviving service did not process and finish the next request") {
            prepared.get() == 1 && shadowOf(service).isStoppedBySelf
        }
        assertEquals(2, shadowOf(service).stopSelfResultId)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(AudioDiagnosticBuffer.export(context).contains("SERVICE_START status=FAILED"))
    }

    @Test fun promotionFailureFallsBackOutstandingBurstOnlyOnce() {
        val request = playback("failed")
        AlarmAudioOwnership.register(context, request)
        ServiceControl.systemLatestStart = 2
        ServiceControl.failPromotion = true
        service.onStartCommand(intent("failed"), 0, 1)
        service.onStartCommand(intent("failed"), 0, 2)
        val notifications = context.getSystemService(android.app.NotificationManager::class.java)
        assertEquals(1, notifications.activeNotifications.count { it.tag.startsWith("fallback:") })
        assertEquals(1, AudioDiagnosticBuffer.export(context).lines().count { it.contains("FALLBACK_POST status=ACCEPTED") })
        AlarmNotifications.cancel(context, 1, false, "failed")
        assertFalse(notifications.activeNotifications.any { it.tag.startsWith("fallback:") })
    }

    @Test fun promotionFailureCancelsOutstandingPreparationButNewStartCanFinish() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val stale = CompletableDeferred<AlarmPlaybackRequest?>()
        val preparedNew = AtomicInteger()
        service.preparePlayback = { token, _ ->
            if (token == "old") {
                entered.countDown()
                try { stale.await() } finally { cancelled.countDown() }
            } else {
                preparedNew.incrementAndGet()
                null
            }
        }
        ServiceControl.systemLatestStart = 1
        service.onStartCommand(intent("old"), 0, 1)
        awaitCondition("Old request never began preparation") { entered.count == 0L }

        ServiceControl.systemLatestStart = 3
        ServiceControl.failPromotion = true
        service.onStartCommand(intent("failed"), 0, 2)
        ServiceControl.failPromotion = false
        service.onStartCommand(intent("new"), 0, 3)
        stale.complete(playback("old"))
        awaitCondition("Cancelled preparation blocked the newer request or idle cleanup") {
            cancelled.count == 0L && preparedNew.get() == 1 && shadowOf(service).isStoppedBySelf
        }
        assertEquals(3, shadowOf(service).stopSelfResultId)
        assertNull(shadowOf(context.getSystemService(AudioManager::class.java)).lastAudioFocusRequest)
    }

    @Test fun cancellationBeforePreparationStartsStillReleasesIdleAccounting() {
        val entered = AtomicInteger()
        service.preparePlayback = { _, _ -> entered.incrementAndGet(); null }
        ServiceControl.systemLatestStart = 2
        service.onStartCommand(intent("cancel-before-body"), 0, 1)
        ServiceControl.failPromotion = true
        service.onStartCommand(intent("failed"), 0, 2)
        awaitCondition("A cancelled job leaked outstanding preparation state") {
            shadowOf(service).isStoppedBySelf
        }
        assertEquals(0, entered.get())
        assertEquals(2, shadowOf(service).stopSelfResultId)
    }

    @Test fun overlappingPreparationsDoNotStopServiceUntilBothComplete() {
        val first = CompletableDeferred<AlarmPlaybackRequest?>()
        val second = CompletableDeferred<AlarmPlaybackRequest?>()
        val entered = AtomicInteger()
        service.preparePlayback = { token, _ ->
            entered.incrementAndGet()
            if (token == "first") first.await() else second.await()
        }
        ServiceControl.systemLatestStart = 2
        service.onStartCommand(intent("first"), 0, 1)
        service.onStartCommand(intent("second"), 0, 2)
        awaitCondition("Overlapping requests never started") { entered.get() == 2 }
        second.complete(null)
        awaitCondition("Second request never completed") {
            ReflectionHelpers.getField<Set<*>>(service, "preparations").size == 1
        }
        assertFalse(shadowOf(service).isStoppedBySelf)
        first.complete(null)
        awaitCondition("Last completion failed to stop the latest start") { shadowOf(service).isStoppedBySelf }
        assertEquals(2, shadowOf(service).stopSelfResultId)
    }

    @Test fun dismissalDuringPreparationPreventsLatePlaybackWithoutCancellingServiceScope() {
        val gate = CompletableDeferred<AlarmPlaybackRequest?>()
        val entered = CountDownLatch(1)
        service.preparePlayback = { _, _ -> entered.countDown(); gate.await() }
        ServiceControl.systemLatestStart = 2
        service.onStartCommand(intent("dismissed"), 0, 1)
        awaitCondition("Request never began preparation") { entered.count == 0L }
        AlarmPlaybackService.dismiss("dismissed")
        main.idle()
        gate.complete(playback("dismissed"))
        val later = AtomicInteger()
        service.preparePlayback = { _, _ -> later.incrementAndGet(); null }
        service.onStartCommand(intent("later"), 0, 2)
        awaitCondition("Dismissal or a stale completion prevented newer work") {
            later.get() == 1 && shadowOf(service).isStoppedBySelf
        }
        assertNull(shadowOf(context.getSystemService(AudioManager::class.java)).lastAudioFocusRequest)
    }

    @Test fun destructionCancelsPreparationAndMakesQueuedCallbacksHarmless() {
        val gate = CompletableDeferred<AlarmPlaybackRequest?>()
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        service.preparePlayback = { _, _ ->
            entered.countDown()
            try { gate.await() } finally { cancelled.countDown() }
        }
        ServiceControl.systemLatestStart = 1
        service.onStartCommand(intent("destroyed"), 0, 1)
        awaitCondition("Request never began preparation") { entered.count == 0L }
        AlarmPlaybackService.dismiss("destroyed") // Intentionally leave the callback queued.
        service.onDestroy()
        destroyed = true
        val stopCount = ServiceControl.stopAttempts.size
        gate.complete(playback("destroyed"))
        awaitCondition("Service destruction did not cancel outstanding preparation") { cancelled.count == 0L }
        main.idle()
        assertEquals(stopCount, ServiceControl.stopAttempts.size)
        assertNull(shadowOf(context.getSystemService(AudioManager::class.java)).lastAudioFocusRequest)
    }

    @Test fun failedPromotionStopsOldBurstAndItsTimeoutCannotStopTheReplacementBurst() {
        val vibrator = shadowOf(context.getSystemService(Vibrator::class.java))
        vibrator.setHasVibrator(true)
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        service.preparePlayback = { token, _ -> playback(token) }
        ServiceControl.systemLatestStart = 1
        service.onStartCommand(intent("old"), 0, 1)
        awaitCondition("Old burst never started") { vibrator.isVibrating }
        main.idleFor(Duration.ofSeconds(10))
        ServiceControl.systemLatestStart = 3
        ServiceControl.failPromotion = true
        service.onStartCommand(intent("failed"), 0, 2)
        assertFalse(vibrator.isVibrating)
        ServiceControl.failPromotion = false
        service.onStartCommand(intent("replacement"), 0, 3)
        awaitCondition("Replacement burst never started") { vibrator.isVibrating }
        val focusDiagnostics = AudioDiagnosticBuffer.export(context).lines().filter { it.contains("FOCUS_REQUEST") }
        assertTrue(focusDiagnostics.first().contains("serviceState=PROMOTED startId=1"))
        assertTrue(focusDiagnostics.last().contains("serviceState=PROMOTED startId=3"))
        main.idleFor(Duration.ofSeconds(20))
        assertTrue("Old timeout stopped the newer burst", vibrator.isVibrating)
        assertFalse(shadowOf(service).isStoppedBySelf)
        main.idleFor(Duration.ofSeconds(10))
        awaitCondition("Replacement timeout failed to stop output and service") {
            !vibrator.isVibrating && shadowOf(service).isStoppedBySelf
        }
        assertEquals(3, shadowOf(service).stopSelfResultId)
    }

    @Implements(Service::class)
    class ServiceControl : ShadowService() {
        @Implementation override fun startForeground(id: Int, notification: Notification) {
            if (failPromotion) throw SecurityException("Foreground promotion denied")
            super.startForeground(id, notification)
        }

        @Implementation override fun stopSelf(startId: Int) {
            stopAttempts.add(startId)
            if (startId == systemLatestStart) super.stopSelf(startId)
        }

        @Implementation override fun stopSelfResult(startId: Int): Boolean {
            stopAttempts.add(startId)
            return startId == systemLatestStart && super.stopSelfResult(startId)
        }

        companion object {
            var failPromotion = false
            var systemLatestStart = 0
            val stopAttempts = mutableListOf<Int>()
        }
    }
}
