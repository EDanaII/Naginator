package com.EDanaII.pillreminder

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
internal fun AboutNaginatorSection() {
    val context = LocalContext.current
    val options = rememberDeveloperOptions()
    var taps by rememberSaveable { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    Text("About Naginator", style = MaterialTheme.typography.titleMedium)
    TextButton(onClick = {
        if (!options.enabled) {
            taps++
            if (taps >= 7) {
                DeveloperSettings.setEnabled(context, true)
                taps = 0
            }
        }
    }) { Text("Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})") }
    Text("Commit ${BuildConfig.GIT_REVISION}")
    if (options.enabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Developer Mode")
            Switch(checked = true, onCheckedChange = {
                DeveloperSettings.setEnabled(context, it)
                taps = 0
                status = null
            }, modifier = Modifier.semantics { contentDescription = "Developer Mode" })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Detailed diagnostics")
            Switch(checked = options.detailed, onCheckedChange = { DeveloperSettings.setDetailed(context, it) },
                modifier = Modifier.semantics { contentDescription = "Detailed diagnostics" })
        }
        TextButton(onClick = { status = NotificationAudioExperiment.schedule(context) }) {
            Text("Diagnostic: system alarm audio in 1 minute")
        }
        TextButton(onClick = {
            NotificationAudioExperiment.cancel(context)
            status = "Diagnostic notification test canceled."
        }) { Text("Cancel diagnostic notification test") }
        TextButton(onClick = {
            status = try {
                AudioDiagnosticBuffer.copy(context)
                "Audio diagnostics copied. Paste them into ChatGPT."
            } catch (_: Exception) { "Could not copy audio diagnostics. Please try again." }
        }) { Text("Copy audio diagnostics") }
        status?.let { Text(it) }
    }
}
