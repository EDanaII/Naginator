package com.EDanaII.pillreminder

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun AlarmPermissionsSection(activity: ComponentActivity) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Only request history is persisted; grants always come from Android.
    val preferences = remember(activity) {
        activity.getSharedPreferences("permission_requests", Context.MODE_PRIVATE)
    }
    var notificationRequested by rememberSaveable {
        mutableStateOf(preferences.getBoolean("notifications_requested", false))
    }
    // Presentation snapshot, refreshed on resume and after requests.
    var status by remember(activity) { mutableStateOf(AlarmPermissions.read(activity)) }
    var showRationale by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationRationale by remember(activity) {
        mutableStateOf(Build.VERSION.SDK_INT >= 33 &&
            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationRationale = Build.VERSION.SDK_INT >= 33 &&
            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        status = AlarmPermissions.read(activity)
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationRequested = true
            preferences.edit().putBoolean("notifications_requested", true).apply()
            try {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: SecurityException) {
                error = "Android could not open the notification permission request."
            }
        }
    }

    fun openSettings(intent: Intent) {
        error = null
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(AlarmPermissions.appSettings(activity))
            } catch (_: ActivityNotFoundException) {
                error = activity.getString(R.string.settings_unavailable, activity.getString(R.string.app_name))
            } catch (_: SecurityException) {
                error = "Android blocked access to settings. Open Settings manually."
            }
        } catch (_: SecurityException) {
            error = "Android blocked access to settings. Open Settings manually."
        }
    }

    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = AlarmPermissions.read(activity)
                notificationRationale = Build.VERSION.SDK_INT >= 33 &&
                    activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        status = AlarmPermissions.read(activity)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("Allow medication notifications?") },
            text = { Text(stringResource(R.string.notification_permission_rationale, stringResource(R.string.app_name))) },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    requestNotifications()
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) { Text("Not now") }
            }
        )
    }

    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide permissions" else "Permissions")
    }
    if (expanded) {
        Column {
            Text("Alarm permissions", style = MaterialTheme.typography.titleMedium)
            Text("Allow scheduled medication alerts. Android settings control sound and full-screen presentation.")
            Spacer(Modifier.height(8.dp))
            Text("Notifications: " + when {
                status.notificationsAllowed -> "Allowed"
                notificationRequested -> "Denied or disabled"
                else -> "Not allowed"
            })
            Text("Show which pills are due, including while the app is closed.")
            if (status.notificationsAllowed) {
                TextButton(onClick = {
                    openSettings(AlarmPermissions.notificationSettings(activity))
                }) { Text("Notification and sound settings") }
            }
            if (!status.notificationsAllowed) {
                TextButton(onClick = {
                    // Recheck at invocation; the display snapshot is not authorization.
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        when {
                            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ->
                                showRationale = true
                            notificationRequested -> openSettings(AlarmPermissions.appSettings(activity))
                            else -> requestNotifications()
                        }
                    } else {
                        openSettings(AlarmPermissions.notificationSettings(activity))
                    }
                }) {
                    Text(if ((notificationRequested && !notificationRationale) || Build.VERSION.SDK_INT < 33)
                        "Notification settings" else "Allow notifications")
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Exact alarms: " + if (status.exactAlarmsAllowed) "Allowed" else "Not allowed")
            Text(if (Build.VERSION.SDK_INT >= 31)
                "Allow medication alerts at their scheduled times."
                else "No separate exact-alarm access is required on this Android version.")
            if (!status.exactAlarmsAllowed && Build.VERSION.SDK_INT >= 31) {
                TextButton(onClick = {
                    if (!AlarmPermissions.exactAlarmsAllowed(activity)) {
                        AlarmPermissions.exactAlarmSettings(activity)?.let { openSettings(it) }
                    }
                }) { Text("Alarms & reminders settings") }
            }

            Spacer(Modifier.height(8.dp))
            Text("Full-screen alerts: " + if (status.fullScreenAllowed) "Allowed" else "Not allowed")
            Text(if (Build.VERSION.SDK_INT >= 34)
                "Allow a prominent alarm screen when Android permits it. This does not guarantee full-screen display."
                else "No separate full-screen access setting is required on this Android version.")
            if (!status.fullScreenAllowed && Build.VERSION.SDK_INT >= 34) {
                TextButton(onClick = {
                    if (!AlarmPermissions.fullScreenAllowed(activity)) {
                        AlarmPermissions.fullScreenSettings(activity)?.let { openSettings(it) }
                    }
                }) { Text("Full-screen alert settings") }
            }
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        }
    }
}
