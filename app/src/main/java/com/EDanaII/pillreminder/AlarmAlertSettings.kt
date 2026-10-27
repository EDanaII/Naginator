package com.EDanaII.pillreminder

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
fun AlarmAlertSettings(soundUri: String?, vibration: Boolean, escalation: Boolean, enabled: Boolean,
    onSound: (String?) -> Unit, onVibration: (Boolean) -> Unit, onEscalation: (Boolean) -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val picked = if (Build.VERSION.SDK_INT >= 33)
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            val resultFlags = result.data?.flags ?: 0
            AlarmSoundDiagnostics.event(context, "PICKER_RESULT selection=${AlarmSoundDiagnostics.selection(picked?.toString())} " +
                "readGrant=${resultFlags.and(Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0} " +
                "persistableGrant=${resultFlags.and(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0}")
            if (picked != null && result.data?.flags?.and(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                try {
                    context.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    AlarmSoundDiagnostics.event(context, "PICKER_GRANT persistRead=SUCCESS")
                } catch (exception: SecurityException) {
                    AlarmSoundDiagnostics.failure(context, "PICKER_GRANT persistRead=FAILED", exception)
                }
            }
            onSound(picked?.takeUnless { RingtoneManager.isDefault(it) }?.toString())
        } else {
            AlarmSoundDiagnostics.event(context, "PICKER_RESULT canceled=true selectionUnchanged=true")
        }
    }
    val title = remember(soundUri) {
        if (soundUri == null) "System alarm sound" else try {
            RingtoneManager.getRingtone(context, Uri.parse(soundUri))?.getTitle(context) ?: "Selected alarm sound"
        } catch (_: Exception) { "Selected alarm sound (default if unavailable)" }
    }
    Text("Alarm alerts", style = MaterialTheme.typography.titleMedium)
    TextButton(enabled = enabled, onClick = {
        picker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                soundUri?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)))
    }) { Text("Alarm sound: $title") }
    Text("Tap a tone in the Android picker to preview it.", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Vibration", Modifier.weight(1f))
        Switch(checked = vibration, onCheckedChange = onVibration, enabled = enabled)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Escalate repeated alerts", Modifier.weight(1f))
        Switch(checked = escalation, onCheckedChange = onEscalation, enabled = enabled)
    }
    Text("Each alert lasts up to 30 seconds. Repeats continue until Dismiss. With escalation off, " +
        "all alerts use 25% gain and 1 second vibration / 9 seconds pause.", style = MaterialTheme.typography.bodySmall)
}
