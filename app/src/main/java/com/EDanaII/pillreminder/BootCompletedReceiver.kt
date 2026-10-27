package com.EDanaII.pillreminder

import android.content.BroadcastReceiver
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Restores from device storage before unlock, then reconciles with Room once available. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val applicationContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i("MedicationAlarm", "Boot recovery: ${AlarmCoordinator(applicationContext).synchronize()}")
            } catch (exception: Exception) {
                Log.e("MedicationAlarm", "Unable to restore alarms after boot", exception)
            } finally {
                pending.finish()
            }
        }
    }
}
