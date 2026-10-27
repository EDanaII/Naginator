package com.EDanaII.pillreminder

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AudioDiagnosticBufferTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context get() = compose.activity

    @Test fun retentionDropsOldestEventsInOrder() {
        for (id in 1..205) AudioDiagnosticBuffer.record(context, "REMINDER_SAVED id=$id selection=NULL")
        val lines = AudioDiagnosticBuffer.export(context).lines().filter { it.contains("REMINDER_SAVED") }
        assertEquals(200, lines.size)
        assertTrue(lines.first().endsWith("id=6 selection=NULL"))
        assertTrue(lines.last().endsWith("id=205 selection=NULL"))
    }

    @Test fun newestTwoHundredSurviveReopenedConnectionsAndConcurrentWriters() {
        val writerContext = context.applicationContext
        val pool = Executors.newFixedThreadPool(4)
        try {
            val writes = (1..240).map { id -> pool.submit {
                AudioDiagnosticBuffer.record(writerContext, "REMINDER_SAVED id=$id selection=NULL")
            } }
            writes.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        // Every operation closes its database connection; export reopens the on-disk store.
        AudioDiagnosticBuffer.record(context, "REMINDER_SAVED id=999 selection=CUSTOM")
        val exported = AudioDiagnosticBuffer.export(context.createDeviceProtectedStorageContext())
        val lines = exported.lines().filter { it.contains("REMINDER_SAVED") }
        assertEquals(200, lines.size)
        assertTrue(lines.last().endsWith("id=999 selection=CUSTOM"))
        assertTrue(lines.all { it.matches(Regex("\\d{4}-.*Z REMINDER_SAVED .*")) })
        assertTrue(File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "audio_diagnostics.db").exists())
    }

    @Test fun onlySanitizedExistingEventsReachDiskAndClipboard() {
        val run = AlarmSoundDiagnostics.nextRun()
        assertNotEquals(run, AlarmSoundDiagnostics.nextRun())
        AlarmSoundDiagnostics.event(context, "PLAYBACK_BEGIN run=$run selection=CUSTOM unlocked=true gain=0.25")
        AlarmSoundDiagnostics.failure(context,
            "CANDIDATE_FAILED run=$run source=CUSTOM stage=SET_DATA_SOURCE gain=0.25",
            SecurityException("content://secret-provider/medication.mp3", java.io.IOException("Take secret medication")))
        for (bad in listOf("REMINDER_SAVED id=1 selection=medication.mp3",
            "PLAYBACK_BEGIN uri=content://secret-provider/medication.mp3",
            "REMINDER_SAVED name=SecretMedication instructions=TakeNow", "SecretMedication")) {
            AudioDiagnosticBuffer.record(context, bad)
        }
        AudioDiagnosticBuffer.copy(context)
        val text = context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertTrue(text.contains("run=$run"))
        assertTrue(text.contains("exception=java.lang.SecurityException causeType=java.io.IOException"))
        for (secret in listOf("content://", "secret-provider", "medication.mp3", "Take secret", "SecretMedication", "TakeNow")) {
            assertFalse(text.contains(secret))
        }
        val file = File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "audio_diagnostics.db")
        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("secret-provider"))
        assertFalse(raw.contains("SecretMedication"))
    }

    @Test fun permissionsActionCopiesAndConfirmsEvenWhenBufferIsEmpty() {
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { AlarmPermissionsSection(compose.activity) }
        } }
        compose.onNodeWithText("Permissions", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Copy audio diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Audio diagnostics copied. Paste them into ChatGPT.").performScrollTo().assertIsDisplayed()
        val text = context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertTrue(text.startsWith("NaginatorAudio diagnostics"))
        assertTrue(text.contains("No audio diagnostic events recorded yet."))
    }
}
