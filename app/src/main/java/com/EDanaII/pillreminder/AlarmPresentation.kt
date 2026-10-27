package com.EDanaII.pillreminder

import android.app.KeyguardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.DateFormat
import java.util.Date

@Composable
fun rememberAlarmForeground(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val keyguard = remember(context) { context.getSystemService(KeyguardManager::class.java) }
    var foreground by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !keyguard.isKeyguardLocked)
    }
    DisposableEffect(lifecycle, keyguard) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !keyguard.isKeyguardLocked
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return foreground
}

/** Foreground Compose UI only; never attempts an Activity launch from the background. */
@Composable
fun ForegroundAlarmHost(
    alarms: List<ActiveAlarm>,
    foreground: Boolean,
    isWorking: Boolean,
    onDismiss: (AlarmOccurrence) -> Unit,
    content: @Composable (onOpen: (AlarmOccurrence) -> Unit) -> Unit
) {
    var hiddenToken by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedToken by rememberSaveable { mutableStateOf<String?>(null) }
    content { occurrence ->
        selectedToken = occurrence.token
        hiddenToken = null
    }
    val alarm = alarms.firstOrNull { it.occurrence.token == selectedToken }
        ?: alarms.firstOrNull()?.takeIf { it.occurrence.token != hiddenToken }
    if (foreground && alarm != null) {
        AlertDialog(
            onDismissRequest = {
                hiddenToken = alarm.occurrence.token
                selectedToken = null
            },
            title = { Text(if (alarm.occurrence.test) "Active test alarm" else "Medication alarm") },
            text = {
                AlarmDetails(alarm, isWorking, { onDismiss(alarm.occurrence) })
            },
            confirmButton = {
                TextButton(onClick = {
                    hiddenToken = alarm.occurrence.token
                    selectedToken = null
                }) { Text("Show reminders") }
            }
        )
    }
}

@Composable
fun AlarmDetails(
    alarm: ActiveAlarm,
    isWorking: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text(alarm.reminder.name, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(alarm.reminder.instructions, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("Scheduled: " + DateFormat.getDateTimeInstance().format(Date(alarm.occurrence.scheduledTime)))
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDismiss, enabled = !isWorking,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
            Text("Dismiss", style = MaterialTheme.typography.headlineSmall)
        }
        Text("Sound and notification stop automatically after 30 seconds. You can still dismiss this alarm here.")
    }
}
