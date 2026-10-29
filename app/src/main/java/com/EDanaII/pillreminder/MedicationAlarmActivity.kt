package com.EDanaII.pillreminder

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import com.EDanaII.pillreminder.ui.theme.PillReminderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MedicationAlarmActivity : ComponentActivity() {
    private var active by mutableStateOf<ActiveAlarm?>(null)
    private var observation: Job? = null
    private var dismissing by mutableStateOf(false)
    private var presentationFinishReason = "OTHER"
    private var presentationFinishRecorded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PresentationDiagnostics.activity(this, "CREATE")
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        setContent {
            PillReminderTheme {
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)
                        .verticalScroll(rememberScrollState()).padding(24.dp)) {
                        val alarm = active
                        if (alarm == null) {
                            Text("Loading alarm…")
                        } else {
                            Text(if (alarm.occurrence.test) "Test reminder" else "Medication reminder",
                                style = MaterialTheme.typography.headlineLarge)
                            Spacer(Modifier.height(24.dp))
                            Text(alarm.reminder.name, style = MaterialTheme.typography.headlineMedium)
                            Spacer(Modifier.height(16.dp))
                            Text(alarm.reminder.instructions, style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(24.dp))
                            Text("Scheduled: " + DateFormat.getDateTimeInstance()
                                .format(Date(alarm.occurrence.scheduledTime)))
                            Spacer(Modifier.height(24.dp))
                            Button(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                                enabled = !dismissing,
                                onClick = {
                                    dismissing = true
                                    lifecycleScope.launch {
                                        withContext(Dispatchers.IO) {
                                            AlarmCoordinator(applicationContext).dismiss(
                                                alarm.reminder.id, alarm.occurrence.test,
                                                alarm.occurrence.token)
                                        }
                                        presentationFinishReason = "DISMISSED"
                                        finish()
                                    }
                                }
                            ) { Text("Dismiss", style = MaterialTheme.typography.headlineSmall) }
                            Text("Sound and notification stop automatically after 30 seconds.")
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PresentationDiagnostics.activity(this, "RESUME")
        val occurrence = intent.getStringExtra(AlarmCoordinator.EXTRA_OCCURRENCE)
            ?.let { AlarmCoordinator.decode(it) }
        if (occurrence == null) {
            presentationFinishReason = "INVALID_OCCURRENCE"
            finish()
            return
        }
        observation?.cancel()
        observation = lifecycleScope.launch {
            AlarmCoordinator(applicationContext).observeState().collect { state ->
                active = state.activeAlarms.firstOrNull { it.occurrence.token == occurrence.token }
                if (active == null) {
                    presentationFinishReason = "NO_ACTIVE_OCCURRENCE"
                    finish()
                }
            }
        }
    }

    override fun onPause() {
        observation?.cancel()
        observation = null
        super.onPause()
    }

    override fun finish() {
        if (!presentationFinishRecorded) {
            presentationFinishRecorded = true
            PresentationDiagnostics.activity(this, "FINISH", presentationFinishReason)
        }
        super.finish()
    }
}
