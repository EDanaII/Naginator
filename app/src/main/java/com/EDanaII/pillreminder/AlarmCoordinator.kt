package com.EDanaII.pillreminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AlarmOccurrence(
    val reminderId: Long,
    val scheduledTime: Long,
    val token: String,
    val test: Boolean,
    val configuration: String,
    // Occurrence-local test policy is persisted before delivery and retained in NagState.
    val nagIntervalOverrideMinutes: Int? = null,
    val forceEscalation: Boolean = false
)

data class ActiveAlarm(val reminder: Reminder, val occurrence: AlarmOccurrence)

data class AlarmUiState(
    val activeAlarms: List<ActiveAlarm> = emptyList(),
    val pendingTests: List<AlarmOccurrence> = emptyList(),
    val pendingDoses: List<AlarmOccurrence> = emptyList()
)

/** Serializes database maintenance with delivery so queued stale broadcasts cannot alert. */
class AlarmCoordinator(
    context: Context,
    dao: ReminderDao? = null,
    private val requestPlayback: (Context, String, Long) -> Boolean = AlarmPlaybackService::request
) {
    private val context = context.applicationContext
    private val reminderDao by lazy {
        check(DirectBootAlarmStore.isUnlocked(this.context)) { "Credential storage is locked" }
        dao ?: ReminderDatabase.getInstance(this.context).reminderDao()
    }
    private val manager = context.getSystemService(AlarmManager::class.java)
    private val runtime = DirectBootAlarmStore(context)
    private val preferences = runtime.preferences

    /** Observes persisted state across coordinator instances, including receiver delivery. */
    fun observeState(): Flow<AlarmUiState> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { uiState() }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun uiState(): AlarmUiState = lock.withLock {
        val reminders = if (DirectBootAlarmStore.isUnlocked(context)) reconcileDefinitions()
            .associateBy { it.id } else runtime.definitions().associate { it.id to it.genericReminder() }
        fun valid(kind: String): List<AlarmOccurrence> =
            preferences.all.filterKeys { it.startsWith("$kind:") }.values
                .filterIsInstance<String>().mapNotNull { decode(it) }
                .filter {
                    val reminder = reminders[it.reminderId]
                    reminder != null && reminder.enabled && it.configuration == runtime.definition(it.reminderId)?.revision
                }.sortedBy { it.scheduledTime }
        AlarmUiState(
            valid("active").map { ActiveAlarm(reminders.getValue(it.reminderId), it) },
            valid("scheduled").filter { it.test },
            valid("scheduled").filter { !it.test }
        )
    }

    suspend fun cancelPendingTest(id: Long, token: String): String = lock.withLock {
        if (DirectBootAlarmStore.isUnlocked(context)) reconcileDefinitions()
        if (scheduled(id, true)?.token == token) {
            cancel(id, true)
            "Pending test canceled. Normal dose times are unchanged."
        } else "That test is no longer pending."
    }

    suspend fun save(reminder: Reminder): String = lock.withLock {
        requireUnlocked()
        if (reminder.id != 0L) invalidate(reminder.id)
        val saved = if (reminder.id == 0L) {
            reminder.copy(id = reminderDao.insert(reminder))
        } else {
            check(reminderDao.update(reminder.id, reminder.name, reminder.instructions,
                reminder.startTimeMillis, reminder.doseIntervalHours,
                reminder.nagIntervalMinutes, reminder.enabled, reminder.alarmSoundUri,
                reminder.vibrationEnabled, reminder.escalationEnabled) == 1)
            reminder
        }
        Log.i(TAG, "Saved id=${saved.id} edit=${reminder.id != 0L} enabled=${saved.enabled} " +
            "start=${saved.startTimeMillis} intervalHours=${saved.doseIntervalHours}")
        AlarmSoundDiagnostics.event(context, "REMINDER_SAVED id=${saved.id} selection=${AlarmSoundDiagnostics.selection(saved.alarmSoundUri)}")
        cancelAll(saved.id)
        installDefinition(saved)
        scheduleReminder(saved) ?: if (saved.enabled) "Reminder saved and next dose scheduled."
            else "Reminder disabled; its alarms are canceled."
    }

    suspend fun delete(id: Long): String = lock.withLock {
        requireUnlocked()
        invalidate(id)
        check(reminderDao.delete(id) == 1)
        "Reminder deleted and its alarms canceled."
    }

    suspend fun setEnabled(id: Long, enabled: Boolean): String = lock.withLock {
        requireUnlocked()
        invalidate(id)
        check(reminderDao.setEnabled(id, enabled) == 1)
        val reminder = checkNotNull(reminderDao.findById(id))
        installDefinition(reminder)
        scheduleReminder(reminder) ?:
            if (enabled) "Reminder enabled and next dose scheduled."
            else "Reminder disabled; its alarms are canceled."
    }

    /** Reconciles OS alarms on app resume and after boot without changing persisted dose anchors. */
    suspend fun synchronize(): String = lock.withLock {
        val reminders = if (DirectBootAlarmStore.isUnlocked(context)) reconcileDefinitions()
            else runtime.definitions().map { it.genericReminder() }
        upgradeActiveStates()
        val ids = reminders.map { it.id }.toSet()
        preferences.all.values.filterIsInstance<String>().mapNotNull { decode(it) }
            .map { it.reminderId }.distinct().filter { it !in ids }.forEach { cancelAll(it) }
        var problem: String? = null
        reminders.forEach { reminder ->
            val expected = scheduled(reminder.id, false)
            val active = read("active", reminder.id, false)
            val test = scheduled(reminder.id, true) ?: read("active", reminder.id, true)
            if (!reminder.enabled) {
                cancelAll(reminder.id)
            } else {
                if (listOfNotNull(expected, active, test).any {
                    it.configuration != runtime.definition(reminder.id)?.revision
                }) cancelAll(reminder.id)
                val reason = unavailableReason()
                if (reason != null) {
                    problem = reason
                } else {
                    val current = scheduled(reminder.id, false)
                    val result = if (current == null) scheduleReminder(reminder)
                        else arm(current)
                    if (result != null) problem = result
                    scheduled(reminder.id, true)?.let { arm(it)?.let { message -> problem = message } }
                }
            }
        }
        activeStates().forEach { state ->
            if (state.occurrence.configuration == runtime.definition(state.occurrence.reminderId)?.revision &&
                state.nextNagTime > 0 && unavailableReason() == null) armNag(state)
        }
        // Wake observers when first unlock changes generic presentation into Room-backed details.
        check(preferences.edit().putLong("reconciled", System.nanoTime()).commit())
        problem ?: if (reminders.any { it.enabled })
            "Enabled reminders scheduled. Alarm sound is limited to 30 seconds."
        else "No enabled reminders to schedule."
    }

    suspend fun testInOneMinute(id: Long, now: Long = System.currentTimeMillis()): String =
        scheduleTest(id, now, accelerated = false)

    suspend fun testEscalation(id: Long, now: Long = System.currentTimeMillis()): String =
        scheduleTest(id, now, accelerated = true)

    private suspend fun scheduleTest(id: Long, now: Long, accelerated: Boolean): String = lock.withLock {
        requireUnlocked()
        reconcileDefinitions()
        val reminder = reminderDao.findById(id) ?: return@withLock "Reminder no longer exists."
        if (!reminder.enabled) return@withLock "Enable this reminder before testing."
        if (read("active", id, true)?.configuration == runtime.definition(id)?.revision) {
            return@withLock "A test alarm is already active. Open or dismiss it before testing again."
        }
        unavailableReason()?.let { return@withLock it }
        cancel(id, true)
        val occurrence = occurrence(reminder, Math.addExact(now, 60_000L), true).copy(
            nagIntervalOverrideMinutes = if (accelerated) 1 else null,
            forceEscalation = accelerated)
        arm(occurrence) ?: if (accelerated)
            "Escalation test scheduled in 1 minute, repeating every minute until dismissed. Normal dose times are unchanged."
        else "Test alarm scheduled in 1 minute. Normal dose times are unchanged."
    }

    suspend fun deliver(incoming: AlarmOccurrence, now: Long = System.currentTimeMillis()): Boolean = lock.withLock {
        if (DirectBootAlarmStore.isUnlocked(context)) reconcileDefinitions()
        val expected = scheduled(incoming.reminderId, incoming.test)
        val definition = runtime.definition(incoming.reminderId)
        val reminder = if (DirectBootAlarmStore.isUnlocked(context)) reminderDao.findById(incoming.reminderId)
            else definition?.genericReminder()
        Log.i(TAG, "Received id=${incoming.reminderId} test=${incoming.test} " +
            "scheduled=${incoming.scheduledTime} received=$now")
        val rejection = when {
            expected == null || !sameIdentity(expected, incoming) -> "stale or unregistered occurrence"
            reminder == null -> "reminder deleted"
            !reminder.enabled -> "reminder disabled"
            definition?.revision != expected.configuration -> "configuration changed"
            incoming.configuration.isNotEmpty() && incoming.configuration != expected.configuration &&
                (!DirectBootAlarmStore.isUnlocked(context) || incoming.configuration != legacyConfiguration(reminder)) ->
                "configuration changed"
            else -> null
        }
        if (rejection != null) {
            Log.i(TAG, "Rejected id=${incoming.reminderId} test=${incoming.test}: $rejection")
            return@withLock false
        }
        checkNotNull(reminder)
        checkNotNull(expected)
        if (incoming.scheduledTime > now) {
            // Android consumed this one-shot broadcast. Keep its identity and anchor,
            // but register it again rather than losing a valid occurrence.
            val result = arm(expected)
            Log.w(TAG, "Early delivery id=${incoming.reminderId} test=${incoming.test} " +
                "earlyByMs=${incoming.scheduledTime - now} result=${result ?: "rearmed"}")
            return@withLock false
        }
        // Consume the old occurrence, record active state and the next anchored dose atomically.
        val next = if (!expected.test) occurrence(reminder,
            DoseSchedule.next(reminder.startTimeMillis, reminder.doseIntervalHours,
                maxOf(now, expected.scheduledTime)), false) else null
        val notificationProblem = AlarmNotifications.suppressionReason(context)
        val nagMinutes = expected.nagIntervalOverrideMinutes ?: reminder.nagIntervalMinutes
        val state = NagState(expected, nextNagTime = if (nagMinutes > 0)
            AlertEscalation.nextNag(now, nagMinutes) else 0, lastAlertTime = now)
        val edit = preferences.edit().remove(key("scheduled", expected.reminderId, expected.test))
            .remove(key("retry", expected.reminderId, expected.test))
        // A blocked presentation must not silently acknowledge the dose.
        edit.putString(activeKey(expected.token), state.encode())
        if (next != null) edit.putString(key("retry", next.reminderId, false), encode(next))
        check(edit.commit())
        val schedulingProblem = unavailableReason()
        if (next != null && schedulingProblem == null) arm(next)
        if (state.nextNagTime > 0 && schedulingProblem == null) armNag(state)
        if (schedulingProblem != null) {
            Log.w(TAG, "Initial follow-up registration skipped id=${incoming.reminderId} test=${incoming.test}: " +
                "$schedulingProblem; active state retained, nextDose=${next?.scheduledTime} nextNag=${state.nextNagTime}; " +
                "synchronize after access is restored")
        }
        if (notificationProblem != null) {
            Log.w(TAG, "Initial alert suppressed id=${incoming.reminderId} test=${incoming.test}: $notificationProblem; " +
                "notification and playback skipped, active state retained")
            return@withLock false
        }
        try {
            AlarmNotifications.show(context, reminder, expected)
            requestPlayback(context, expected.token, state.lastAlertTime)
            true
        } catch (exception: SecurityException) {
            Log.w(TAG, "Initial alert suppressed id=${incoming.reminderId} test=${incoming.test}: " +
                "notification posting denied; playback skipped, active state retained", exception)
            false
        }
    }

    suspend fun active(id: Long, test: Boolean, token: String): ActiveAlarm? = lock.withLock {
        if (DirectBootAlarmStore.isUnlocked(context)) reconcileDefinitions()
        upgradeActiveStates()
        val occurrence = activeStates().firstOrNull {
            it.occurrence.reminderId == id && it.occurrence.test == test && it.occurrence.token == token
        }?.occurrence ?: return@withLock null
        val definition = runtime.definition(id) ?: return@withLock null
        val reminder = if (DirectBootAlarmStore.isUnlocked(context)) reminderDao.findById(id)
            else definition.genericReminder()
        reminder ?: return@withLock null
        if (occurrence.token != token || !reminder.enabled ||
            occurrence.configuration != definition.revision) return@withLock null
        ActiveAlarm(reminder, occurrence)
    }

    suspend fun dismiss(id: Long, test: Boolean, token: String) = lock.withLock {
        if (DirectBootAlarmStore.isUnlocked(context)) reconcileDefinitions()
        upgradeActiveStates()
        val state = activeStates().firstOrNull {
            it.occurrence.reminderId == id && it.occurrence.test == test && it.occurrence.token == token
        }
        if (state != null) {
            check(preferences.edit().remove(activeKey(token)).commit())
            cancelNag(state)
            AlarmNotifications.cancel(context, id, test, token)
            AlarmPlaybackService.dismiss(token)
        }
        // Deliberately does not touch the next scheduled dose.
    }

    /** Desired occurrence, including a failed registration awaiting retry; UI lists only confirmed registrations. */
    internal fun scheduled(id: Long, test: Boolean): AlarmOccurrence? = read("scheduled", id, test)

    /** Only the persisted due time can advance a chain, so duplicate/stale broadcasts cannot nag. */
    suspend fun nag(incoming: AlarmOccurrence, due: Long, now: Long = System.currentTimeMillis()): Boolean = lock.withLock {
        if (DirectBootAlarmStore.isUnlocked(context)) reconcileDefinitions()
        upgradeActiveStates()
        fun rejected(reason: String): Boolean {
            Log.w(TAG, "Nag suppressed id=${incoming.reminderId} test=${incoming.test} due=$due: $reason")
            return false
        }
        val state = activeStates().firstOrNull { sameIdentity(it.occurrence, incoming) }
            ?: return@withLock rejected("no matching active occurrence (dismissed, stale, or invalidated)")
        val definition = runtime.definition(incoming.reminderId)
            ?: return@withLock rejected("runtime reminder definition missing")
        val nagMinutes = state.occurrence.nagIntervalOverrideMinutes ?: definition.nagMinutes
        if (state.occurrence.configuration != definition.revision)
            return@withLock rejected("configuration changed")
        if (due <= 0 || due != state.nextNagTime)
            return@withLock rejected("stale or invalid due time; expected=${state.nextNagTime}")
        if (nagMinutes <= 0) return@withLock rejected("nag interval disabled")
        if (due > now) {
            Log.i(TAG, "Early nag deferred id=${incoming.reminderId} due=$due received=$now; re-registering")
            armNag(state)
            return@withLock false
        }
        val updated = state.copy(level = AlertEscalation.next(state.level,
            state.occurrence.forceEscalation || definition.escalationEnabled),
            nextNagTime = AlertEscalation.nextNag(now, nagMinutes), lastAlertTime = now)
        check(preferences.edit().putString(activeKey(incoming.token), updated.encode()).commit())
        val schedulingProblem = unavailableReason()
        if (schedulingProblem == null) armNag(updated)
        else Log.w(TAG, "Next nag registration skipped id=${incoming.reminderId} test=${incoming.test}: " +
            "$schedulingProblem; state advanced level=${updated.level} nextNag=${updated.nextNagTime}; " +
            "synchronize after access is restored")
        AlarmNotifications.suppressionReason(context)?.let {
            return@withLock rejected("$it; notification and playback skipped, advanced active state retained")
        }
        val reminder = if (DirectBootAlarmStore.isUnlocked(context)) reminderDao.findById(incoming.reminderId)
            else definition.genericReminder()
        reminder ?: return@withLock rejected("reminder missing")
        try {
            AlarmNotifications.show(context, reminder, state.occurrence)
        } catch (exception: SecurityException) {
            Log.w(TAG, "Nag notification posting denied id=${incoming.reminderId}; " +
                "playback skipped, advanced active state retained", exception)
            throw exception
        }
        requestPlayback(context, incoming.token, now)
        true
    }

    internal suspend fun playback(token: String, alertAt: Long): AlarmPlaybackRequest? = lock.withLock {
        if (DirectBootAlarmStore.isUnlocked(context)) reconcileDefinitions()
        fun rejected(reason: String): AlarmPlaybackRequest? {
            Log.w(TAG, "Playback request suppressed token=$token alertAt=$alertAt: $reason")
            return null
        }
        val state = activeStates().firstOrNull { it.occurrence.token == token && it.lastAlertTime == alertAt }
            ?: return@withLock rejected("no active occurrence matching token and alert time (dismissed, stale, or invalidated)")
        val definition = runtime.definition(state.occurrence.reminderId)
            ?: return@withLock rejected("runtime reminder definition missing")
        if (definition.revision != state.occurrence.configuration)
            return@withLock rejected("configuration changed")
        AlarmNotifications.suppressionReason(context)?.let { return@withLock rejected(it) }
        val reminder = if (DirectBootAlarmStore.isUnlocked(context)) reminderDao.findById(definition.id)
            else definition.genericReminder()
        reminder ?: return@withLock rejected("reminder missing")
        AlarmSoundDiagnostics.event(context, "PLAYBACK_REQUEST id=${definition.id} test=${state.occurrence.test} " +
            "selection=${AlarmSoundDiagnostics.selection(reminder.alarmSoundUri)} " +
            "unlocked=${DirectBootAlarmStore.isUnlocked(context)} level=${state.level} " +
            "escalation=${state.occurrence.forceEscalation || reminder.escalationEnabled}")
        AlarmPlaybackRequest(state.occurrence, alertAt, state.level, reminder.alarmSoundUri,
            reminder.vibrationEnabled, state.occurrence.forceEscalation || reminder.escalationEnabled,
            DirectBootAlarmStore.isUnlocked(context), reminder)
    }

    internal fun nagState(token: String): NagState? = activeStates().firstOrNull { it.occurrence.token == token }

    private fun activeKey(token: String) = "active:$token"

    private fun activeStates(): List<NagState> = preferences.all.filterKeys { it.startsWith("active:") }
        .values.filterIsInstance<String>().mapNotNull { NagState.decode(it) }.distinctBy { it.occurrence.token }

    /** Move old id-keyed entries without changing tokens; backfill nag settings only after Room is available. */
    private fun upgradeActiveStates(now: Long = System.currentTimeMillis()) {
        val edit = preferences.edit()
        var changed = false
        preferences.all.filterKeys { it.startsWith("active:") }.forEach { (key, value) ->
            val state = (value as? String)?.let { NagState.decode(it) } ?: return@forEach
            val definition = runtime.definition(state.occurrence.reminderId) ?: return@forEach
            val nagMinutes = state.occurrence.nagIntervalOverrideMinutes ?: definition.nagMinutes
            val updated = if (state.nextNagTime == 0L && nagMinutes > 0)
                state.copy(nextNagTime = AlertEscalation.nextNag(now, nagMinutes)) else state
            if (key != activeKey(state.occurrence.token) || state != updated) {
                edit.remove(key).putString(activeKey(state.occurrence.token), updated.encode())
                changed = true
            }
        }
        if (changed) check(edit.commit())
    }

    private fun nagOperation(state: NagState, flags: Int): PendingIntent? {
        val intent = Intent(context, MedicationAlarmReceiver::class.java).setAction(ACTION_NAG)
            .setData(Uri.parse("pillreminder://nag/${state.occurrence.token}"))
            .putExtra(EXTRA_OCCURRENCE, encode(state.occurrence.copy(configuration = "")))
            .putExtra(EXTRA_NAG_TIME, state.nextNagTime)
        return PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun armNag(state: NagState) {
        AlarmSoundDiagnostics.event(context, "REGISTRATION id=${state.occurrence.reminderId} kind=NAG status=RETRY")
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, state.nextNagTime,
                nagOperation(state, PendingIntent.FLAG_UPDATE_CURRENT)!!)
            AlarmSoundDiagnostics.event(context, "REGISTRATION id=${state.occurrence.reminderId} kind=NAG status=REGISTERED")
            Log.i(TAG, "Nag registered id=${state.occurrence.reminderId} test=${state.occurrence.test} " +
                "nextNag=${state.nextNagTime} level=${state.level}")
        } catch (exception: SecurityException) {
            AlarmSoundDiagnostics.event(context, "REGISTRATION id=${state.occurrence.reminderId} kind=NAG status=FAILED")
            Log.w(TAG, "Nag registration denied id=${state.occurrence.reminderId} nextNag=${state.nextNagTime}; " +
                "persisted state retained, synchronize after access is restored", exception)
        }
    }

    private fun cancelNag(state: NagState) {
        nagOperation(state, PendingIntent.FLAG_NO_CREATE)?.let { manager.cancel(it); it.cancel() }
    }

    private fun scheduleReminder(reminder: Reminder, after: Long = System.currentTimeMillis()): String? {
        if (!reminder.enabled) return null
        unavailableReason()?.let {
            Log.w(TAG, "Not scheduled id=${reminder.id}: $it")
            return it
        }
        return try {
            val next = DoseSchedule.next(reminder.startTimeMillis, reminder.doseIntervalHours, after)
            Log.i(TAG, "Calculated id=${reminder.id} start=${reminder.startTimeMillis} " +
                "after=$after next=$next")
            arm(occurrence(reminder, next, false))
        } catch (_: ArithmeticException) {
            "Reminder saved, but its schedule exceeds the supported date range."
        }
    }

    private fun unavailableReason(): String? {
        if (!AlarmPermissions.exactAlarmsAllowed(context))
            return "Alarms are not scheduled. Enable Alarms & reminders access in Permissions."
        return AlarmNotifications.suppressionReason(context)?.let {
            "Alarms are not scheduled: $it. Enable notifications and the Medication alarms channel in Android settings."
        }
    }

    /** Null means successfully armed; there is no inexact fallback. */
    private fun arm(occurrence: AlarmOccurrence): String? {
        // Durable intent is distinct from successful Android registration. Retry the same identity.
        check(preferences.edit().putString(key("retry", occurrence.reminderId, occurrence.test), encode(occurrence))
            .remove(key("scheduled", occurrence.reminderId, occurrence.test)).commit())
        val kind = if (occurrence.test) "TEST" else "DOSE"
        AlarmSoundDiagnostics.event(context, "REGISTRATION id=${occurrence.reminderId} kind=$kind status=RETRY")
        return try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                occurrence.scheduledTime, operation(occurrence, PendingIntent.FLAG_UPDATE_CURRENT)!!)
            check(preferences.edit().putString(key("scheduled", occurrence.reminderId, occurrence.test), encode(occurrence))
                .remove(key("retry", occurrence.reminderId, occurrence.test)).commit())
            AlarmSoundDiagnostics.event(context, "REGISTRATION id=${occurrence.reminderId} kind=$kind status=REGISTERED")
            Log.i(TAG, "Registered id=${occurrence.reminderId} test=${occurrence.test} " +
                "scheduled=${occurrence.scheduledTime}")
            null
        } catch (exception: SecurityException) {
            AlarmSoundDiagnostics.event(context, "REGISTRATION id=${occurrence.reminderId} kind=$kind status=FAILED")
            Log.w(TAG, "Registration denied id=${occurrence.reminderId} test=${occurrence.test}", exception)
            // Permission may change after the live check. Keep the same durable identity for retry.
            "Exact-alarm access was denied. Enable Alarms & reminders access in Permissions."
        }
    }

    private fun cancelAll(id: Long) {
        AlarmPlaybackService.dismissTokens(activeStates().filter { it.occurrence.reminderId == id }
            .map { it.occurrence.token })
        AlarmPlaybackService.dismissReminder(id)
        cancel(id, false)
        cancel(id, true)
    }

    private fun cancel(id: Long, test: Boolean) {
        val affected = activeStates().filter { it.occurrence.reminderId == id && it.occurrence.test == test }
        affected.forEach {
            cancelNag(it)
        }
        val placeholder = AlarmOccurrence(id, 0, "", test, "")
        operation(placeholder, PendingIntent.FLAG_NO_CREATE)?.let {
            manager.cancel(it)
            it.cancel()
        }
        remove("scheduled", id, test)
        remove("retry", id, test)
        remove("active", id, test)
        AlarmPlaybackService.dismissTokens(affected.map { it.occurrence.token })
        AlarmPlaybackService.dismissReminder(id, test)
        AlarmNotifications.cancel(context, id, test)
    }

    private fun operation(occurrence: AlarmOccurrence, flags: Int): PendingIntent? {
        val intent = Intent(context, MedicationAlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("pillreminder://scheduled/${occurrence.reminderId}/${occurrence.test}"))
            .putExtra(EXTRA_OCCURRENCE, encode(occurrence.copy(configuration = "")))
        return PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun occurrence(reminder: Reminder, at: Long, test: Boolean) =
        AlarmOccurrence(reminder.id, at, UUID.randomUUID().toString(), test,
            checkNotNull(runtime.definition(reminder.id)).revision)

    private fun legacyConfiguration(reminder: Reminder): String {
        val values = JSONArray(listOf(
        reminder.id, reminder.name, reminder.instructions, reminder.startTimeMillis,
        reminder.doseIntervalHours, reminder.nagIntervalMinutes, reminder.enabled
        ))
        // Preserve existing opaque revisions/tokens when upgrading with the new default settings.
        if (reminder.alarmSoundUri != null || !reminder.vibrationEnabled || !reminder.escalationEnabled) {
            values.put(reminder.alarmSoundUri ?: JSONObject.NULL).put(reminder.vibrationEnabled).put(reminder.escalationEnabled)
        }
        return values.toString()
    }

    private fun requireUnlocked() = check(DirectBootAlarmStore.isUnlocked(context)) {
        "Unlock the phone before changing reminders"
    }

    /** Only credential storage contains the detailed configuration-to-opaque-revision mapping. */
    private fun revision(reminder: Reminder): String {
        requireUnlocked()
        val configs = context.getSharedPreferences("alarm_configurations", Context.MODE_PRIVATE)
        val definition = legacyConfiguration(reminder)
        val existing = configs.getString("revision:${reminder.id}", null)
        if (existing != null && configs.getString("definition:${reminder.id}", null) == definition) return existing
        val value = UUID.randomUUID().toString()
        check(configs.edit().putString("definition:${reminder.id}", definition)
            .putString("revision:${reminder.id}", value).commit())
        return value
    }

    private fun installDefinition(reminder: Reminder) {
        val edit = preferences.edit().putBoolean("known:${reminder.id}", true)
            .remove("repair:${reminder.id}")
        if (reminder.enabled) {
            val value = RuntimeReminder(reminder.id, reminder.startTimeMillis,
                reminder.doseIntervalHours, revision(reminder), reminder.nagIntervalMinutes,
                reminder.vibrationEnabled, reminder.escalationEnabled)
            edit.putString("definition:${reminder.id}", value.encode())
        } else edit.remove("definition:${reminder.id}")
        check(edit.commit())
    }

    /** Tombstone first: interrupted edits/deletes must never resurrect the previous alarm at boot. */
    private fun invalidate(id: Long) {
        // Cancel token-specific OS nags before removing the ledger entries they identify.
        val affected = activeStates().filter { it.occurrence.reminderId == id }
        affected.forEach {
            cancelNag(it)
        }
        val edit = preferences.edit()
        preferences.all.filterKeys { it.startsWith("active:") }.forEach { (key, value) ->
            if ((value as? String)?.let { decode(it) }?.reminderId == id) edit.remove(key)
        }
        check(edit.remove("definition:$id").putBoolean("known:$id", true)
            .putBoolean("repair:$id", true)
            .remove(key("scheduled", id, false)).remove(key("scheduled", id, true))
            .remove(key("retry", id, false)).remove(key("retry", id, true))
            .remove(key("active", id, false)).remove(key("active", id, true)).commit())
        AlarmPlaybackService.dismissTokens(affected.map { it.occurrence.token })
        cancelAll(id)
    }

    /** Called only after unlock. Import legacy occurrences once; never overwrite locked-mode progress. */
    private suspend fun reconcileDefinitions(): List<Reminder> {
        requireUnlocked()
        val reminders = reminderDao.getAll()
        val ids = reminders.map { it.id }.toSet()
        runtime.definitions().filter { it.id !in ids }.forEach { invalidate(it.id) }
        val legacy = context.getSharedPreferences("alarm_occurrences", Context.MODE_PRIVATE)
        reminders.forEach { reminder ->
            val expectedRevision = revision(reminder)
            val current = runtime.definition(reminder.id)
            val known = preferences.getBoolean("known:${reminder.id}", false)
            if (!reminder.enabled) {
                if (current != null || !known || listOf(false, true).any {
                        read("scheduled", reminder.id, it) != null || read("active", reminder.id, it) != null
                    }) {
                    invalidate(reminder.id)
                    installDefinition(reminder)
                }
            } else if (current == null || current.revision != expectedRevision) {
                val imported = mutableListOf<Pair<String, AlarmOccurrence>>()
                if (!known) {
                    for (kind in listOf("scheduled", "active")) for (test in listOf(false, true)) {
                        legacy.getString(key(kind, reminder.id, test), null)?.let { decode(it) }
                            ?.takeIf { it.reminderId == reminder.id && it.test == test &&
                                it.configuration == legacyConfiguration(reminder) }
                            ?.let { imported.add(kind to it.copy(configuration = expectedRevision)) }
                    }
                }
                // Initial migration must not cancel valid OS alarms/notifications merely because
                // an observer or dismissal is the first entry point after updating the app.
                if (known || imported.isEmpty()) cancelAll(reminder.id)
                val edit = preferences.edit().putString("definition:${reminder.id}",
                    RuntimeReminder(reminder.id, reminder.startTimeMillis, reminder.doseIntervalHours,
                        expectedRevision, reminder.nagIntervalMinutes, reminder.vibrationEnabled,
                        reminder.escalationEnabled).encode()).putBoolean("known:${reminder.id}", true)
                    .remove("repair:${reminder.id}")
                imported.forEach { (kind, value) ->
                    edit.putString(key(kind, reminder.id, value.test), encode(value))
                }
                check(edit.commit())
                if (scheduled(reminder.id, false) == null) scheduleReminder(reminder)
            } else if (current.nagMinutes != reminder.nagIntervalMinutes ||
                current.vibrationEnabled != reminder.vibrationEnabled || current.escalationEnabled != reminder.escalationEnabled) {
                installDefinition(reminder)
            }
            val clean = legacy.edit()
            for (kind in listOf("scheduled", "active")) for (test in listOf(false, true)) {
                clean.remove(key(kind, reminder.id, test))
            }
            check(clean.commit())
        }
        check(legacy.edit().clear().commit())
        upgradeActiveStates()
        return reminders
    }

    private fun sameIdentity(first: AlarmOccurrence, second: AlarmOccurrence): Boolean =
        first.reminderId == second.reminderId && first.scheduledTime == second.scheduledTime &&
            first.token == second.token && first.test == second.test

    private fun remove(kind: String, id: Long, test: Boolean) {
        val edit = preferences.edit().remove(key(kind, id, test))
        if (kind == "active") preferences.all.filterKeys { it.startsWith("active:") }.forEach { (key, value) ->
            val occurrence = (value as? String)?.let { decode(it) }
            if (occurrence?.reminderId == id && occurrence.test == test) edit.remove(key)
        }
        check(edit.commit())
    }

    private fun read(kind: String, id: Long, test: Boolean): AlarmOccurrence? =
        if (kind == "active") activeStates().firstOrNull {
            it.occurrence.reminderId == id && it.occurrence.test == test
        }?.occurrence else (preferences.getString(key(kind, id, test), null)
            ?: if (kind == "scheduled") preferences.getString(key("retry", id, test), null) else null)?.let { decode(it) }

    private fun key(kind: String, id: Long, test: Boolean) = "$kind:$id:$test"

    companion object {
        private const val TAG = "MedicationAlarm"
        private val lock = Mutex()
        const val ACTION_FIRE = "com.EDanaII.pillreminder.FIRE_ALARM"
        const val ACTION_DISMISS = "com.EDanaII.pillreminder.DISMISS_ALARM"
        const val ACTION_NAG = "com.EDanaII.pillreminder.NAG_ALARM"
        const val EXTRA_NAG_TIME = "nag_time"
        const val EXTRA_OCCURRENCE = "occurrence"

        fun encode(value: AlarmOccurrence): String = JSONObject()
            .put("id", value.reminderId).put("at", value.scheduledTime)
            .put("token", value.token).put("test", value.test)
            .put("configuration", value.configuration)
            .put("nagIntervalOverrideMinutes", value.nagIntervalOverrideMinutes)
            .put("forceEscalation", value.forceEscalation).toString()

        fun decode(value: String): AlarmOccurrence? = try {
            val json = JSONObject(value)
            AlarmOccurrence(json.getLong("id"), json.getLong("at"), json.getString("token"),
                json.getBoolean("test"), json.getString("configuration"),
                if (json.getBoolean("test") && json.optInt("nagIntervalOverrideMinutes") == 1) 1 else null,
                json.getBoolean("test") && json.optBoolean("forceEscalation", false))
        } catch (_: org.json.JSONException) { null }
    }
}
