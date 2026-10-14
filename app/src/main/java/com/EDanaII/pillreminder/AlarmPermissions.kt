package com.EDanaII.pillreminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

data class AlarmPermissionStatus(
    val notificationsAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
    val fullScreenAllowed: Boolean
)

/** Live platform checks. Call again before any future permission-protected operation. */
object AlarmPermissions {
    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun exactAlarmsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun fullScreenAllowed(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 34 ->
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        Build.VERSION.SDK_INT >= 29 -> ContextCompat.checkSelfPermission(
            context, Manifest.permission.USE_FULL_SCREEN_INTENT
        ) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    fun read(context: Context) = AlarmPermissionStatus(
        notificationsAllowed(context),
        exactAlarmsAllowed(context),
        fullScreenAllowed(context)
    )

    fun notificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun exactAlarmSettings(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= 31)
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context))
        else null

    fun fullScreenSettings(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= 34)
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context))
        else null

    fun appSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    private fun packageUri(context: Context) = Uri.parse("package:${context.packageName}")
}
