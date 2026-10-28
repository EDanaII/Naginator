package com.EDanaII.pillreminder

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Looper
import androidx.room.Room
import kotlinx.coroutines.runBlocking
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
@Config(sdk = [28], shadows = [NotificationAudioFallbackTest.FocusControl::class])
class NotificationAudioFallbackTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val audio get() = shadowOf(context.getSystemService(AudioManager::class.java))
    private val main get() = shadowOf(Looper.getMainLooper())
    private val fallbacks get() = manager.activeNotifications.filter { it.tag.startsWith("fallback:") }
    private fun request(at: Long = 100) = AlarmPlaybackRequest(
        AlarmOccurrence(1, 100, "fallback-token", true, "revision"), at, 2, null, true, true, true)

    @Before fun reset() {
        FocusControl.fail = false
        AlarmAudioOwnership.resetForTests()
        shadowOf(context.getSystemService(android.os.UserManager::class.java)).setUserUnlocked(true)
    }

    @Test fun grantedFocusUsesApplicationOnly() {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)), ShadowMediaPlayer.MediaInfo(60_000, 0))
        val players = mutableListOf<MediaPlayer>()
        val output = AndroidOutput(context, LoopingAlarmSound(context, { _, _ -> AutoCloseable {} }) {
            MediaPlayer().also { players.add(it) }
        })
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        output.start(request())
        main.idle()
        assertTrue(players.single().isPlaying)
        assertTrue(fallbacks.isEmpty())
        output.stop("DISMISSED")
    }

    @Test fun denialUsesOneNotificationAndRefreshDoesNotRepeatIt() {
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val output = AndroidOutput(context)
        output.start(request())
        assertEquals(1, fallbacks.size)
        AlarmAudioOwnership.fallback(context, request())
        AlarmNotifications.show(context, Reminder(id = 1, name = "Private", instructions = "Details",
            startTimeMillis = 100, doseIntervalHours = 8, nagIntervalMinutes = 1), request().occurrence)
        assertEquals(1, fallbacks.size)
        assertEquals(1, AudioDiagnosticBuffer.export(context).lines().count { it.contains("FALLBACK_POST status=ACCEPTED") })
        assertFalse(AudioDiagnosticBuffer.export(context).contains("PLAYBACK_BEGIN"))
        assertEquals(30_000L, fallbacks.single().notification.timeoutAfter)
        output.stop("DISMISSED")
        assertTrue(fallbacks.isEmpty())
        AlarmAudioOwnership.fallback(context, request())
        assertTrue(fallbacks.isEmpty())
    }

    @Test fun staleFocusAndCancellationCannotAffectNextBurst() {
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        var players = 0
        val output = AndroidOutput(context, LoopingAlarmSound(context, { _, _ -> AutoCloseable {} }) {
            players++; MediaPlayer()
        })
        output.start(request())
        val listener = audio.lastAudioFocusRequest.listener!!
        // Failure decision abandons the old burst before a later callback arrives.
        output.stop("DISMISSED")
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        output.start(request(200))
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        AlarmAudioOwnership.stop(context, request(), "TIMEOUT")
        assertEquals(0, players)
        assertEquals(1, fallbacks.size)
        main.idleFor(Duration.ofSeconds(30))
        assertTrue(fallbacks.isEmpty())
        output.stop("TIMEOUT")
    }

    @Test fun exhaustedPlayerCandidatesUseSystemNotification() {
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val output = AndroidOutput(context, LoopingAlarmSound(context, { _, _ -> AutoCloseable {} }) {
            throw IllegalStateException("No player")
        })
        output.start(request())
        assertEquals(1, fallbacks.size)
        assertTrue(AudioDiagnosticBuffer.export(context).contains("PLAYBACK_EXHAUSTED"))
        output.stop("DISMISSED")
    }

    @Test fun focusExceptionSubmitsFallbackWithoutApplicationPlayback() {
        FocusControl.fail = true
        var players = 0
        val output = AndroidOutput(context, LoopingAlarmSound(context, { _, _ -> AutoCloseable {} }) {
            players++; MediaPlayer()
        })
        output.start(request())
        assertEquals(0, players)
        assertEquals(1, fallbacks.size)
        output.stop("DISMISSED")
    }

    @Test fun silencedFallbackChannelDoesNotCauseAlternateChannelOrPlayer() {
        manager.createNotificationChannel(android.app.NotificationChannel(AlarmNotificationAudio.DEFAULT_CHANNEL,
            "User silent", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val output = AndroidOutput(context)
        output.start(request().copy(vibration = false))
        assertEquals(AlarmNotificationAudio.DEFAULT_CHANNEL, fallbacks.single().notification.channelId)
        assertNull(manager.getNotificationChannel(AlarmNotificationAudio.DEFAULT_CHANNEL).sound)
        assertTrue(AudioDiagnosticBuffer.export(context).contains("soundEnabled=false"))
        output.stop("TIMEOUT")
    }

    @Test fun delayedFocusWithoutGainFallsBackWithinOriginalDeadlineAndIgnoresLateGain() {
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        var players = 0
        val output = AndroidOutput(context, LoopingAlarmSound(context, { _, _ -> AutoCloseable {} }) {
            players++; MediaPlayer()
        })
        output.start(request())
        val listener = audio.lastAudioFocusRequest.listener!!
        main.idleFor(Duration.ofSeconds(10))
        assertEquals(20_000L, fallbacks.single().notification.timeoutAfter)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0, players)
        main.idleFor(Duration.ofSeconds(20))
        assertTrue(fallbacks.isEmpty())
        output.stop("TIMEOUT")
    }

    @Test fun inaccessibleCustomAndRepeatedServiceFailureUseSingleSystemAlert() {
        val request = request().copy(soundUri = "content://private/unavailable")
        AlarmAudioOwnership.register(context, request)
        AlarmAudioOwnership.failedStarts(context, request.token, request.alertAt)
        AlarmAudioOwnership.failedStarts(context, request.token, request.alertAt)
        assertEquals(1, fallbacks.size)
        val channel = manager.getNotificationChannel(fallbacks.single().notification.channelId)
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), channel.sound)
        assertTrue(channel.shouldVibrate())
        assertTrue(AudioDiagnosticBuffer.export(context).contains("FALLBACK_SOUND source=SYSTEM_DEFAULT"))
        AlarmNotifications.cancel(context, 1, true, request.token)
        assertTrue(fallbacks.isEmpty())
    }

    @Test fun accessibleCustomUsesStableChannelAcrossBursts() {
        val file = java.io.File(context.cacheDir, "test-tone").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun openAssetFile(uri: android.net.Uri, mode: String) = android.content.res.AssetFileDescriptor(
                android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length())
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor? = null
            override fun getType(uri: android.net.Uri) = "audio/wav"
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
            override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?,
                selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "test-tone" })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("test-tone", provider)
        val request = request().copy(soundUri = "content://test-tone/alarm")
        AlarmAudioOwnership.register(context, request)
        AlarmAudioOwnership.fallback(context, request)
        val channel = manager.getNotificationChannel(fallbacks.single().notification.channelId)
        assertEquals(android.net.Uri.parse(request.soundUri), channel.sound)
        AlarmAudioOwnership.register(context, request.copy(alertAt = 200))
        AlarmAudioOwnership.fallback(context, request.copy(alertAt = 200))
        assertEquals(channel.id, fallbacks.single().notification.channelId)
        assertFalse(AudioDiagnosticBuffer.export(context).contains("content://test-tone"))
    }

    @Test fun failedServiceStartAndNagRemainAudibleUntilAcknowledged() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, ReminderDatabase::class.java).build()
        try {
            val coordinator = AlarmCoordinator(context, database.reminderDao()) { _, _, _ -> false }
            val now = System.currentTimeMillis()
            coordinator.save(Reminder(name = "Dose", instructions = "Details", startTimeMillis = now + 60_000,
                doseIntervalHours = 8, nagIntervalMinutes = 1))
            val id = database.reminderDao().getAll().single().id
            val occurrence = coordinator.scheduled(id, false)!!
            assertTrue(coordinator.deliver(occurrence, occurrence.scheduledTime))
            assertEquals(1, fallbacks.size)
            main.idleFor(Duration.ofSeconds(30))
            assertTrue(fallbacks.isEmpty())
            val state = coordinator.nagState(occurrence.token)!!
            assertTrue(coordinator.nag(occurrence, state.nextNagTime, state.nextNagTime))
            assertEquals(1, fallbacks.size)
            assertEquals(1, coordinator.nagState(occurrence.token)!!.level)
            coordinator.dismiss(id, false, occurrence.token, expectedAlertAt = occurrence.scheduledTime)
            assertNotNull(coordinator.nagState(occurrence.token)) // Old fallback action is stale.
            coordinator.dismiss(id, false, occurrence.token)
            assertTrue(fallbacks.isEmpty())
            assertNull(coordinator.nagState(occurrence.token))
            assertFalse(coordinator.nag(occurrence, state.nextNagTime, state.nextNagTime))
        } finally { database.close() }
    }

    @Implements(AudioManager::class)
    class FocusControl : ShadowAudioManager() {
        @Implementation override fun requestAudioFocus(request: android.media.AudioFocusRequest): Int {
            if (fail) throw IllegalStateException("Focus unavailable")
            return super.requestAudioFocus(request)
        }
        companion object { var fail = false }
    }
}
