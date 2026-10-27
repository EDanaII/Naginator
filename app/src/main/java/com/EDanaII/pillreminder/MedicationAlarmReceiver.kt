package com.EDanaII.pillreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Explicit immutable PendingIntents only; this receiver is not exported. */
class MedicationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmCoordinator.ACTION_FIRE &&
            intent.action != AlarmCoordinator.ACTION_NAG &&
            intent.action != AlarmCoordinator.ACTION_DISMISS) return
        val occurrence = intent.getStringExtra(AlarmCoordinator.EXTRA_OCCURRENCE)
            ?.let { AlarmCoordinator.decode(it) } ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val coordinator = AlarmCoordinator(context)
                if (intent.action == AlarmCoordinator.ACTION_DISMISS) {
                    coordinator.dismiss(occurrence.reminderId, occurrence.test, occurrence.token)
                } else if (intent.action == AlarmCoordinator.ACTION_NAG) {
                    coordinator.nag(occurrence, intent.getLongExtra(AlarmCoordinator.EXTRA_NAG_TIME, 0))
                } else {
                    coordinator.deliver(occurrence)
                }
            } catch (exception: Exception) {
                Log.e("MedicationAlarm", "Unable to process alarm", exception)
            } finally {
                pending.finish()
            }
        }
    }
}
