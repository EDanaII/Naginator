package com.EDanaII.pillreminder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReminderViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = ReminderDatabase.getInstance(application).reminderDao()
    private val coordinator = AlarmCoordinator(application, dao)
    private val _alarmStatus = MutableStateFlow("Checking alarm access…")
    val alarmStatus: StateFlow<String> = _alarmStatus.asStateFlow()
    private val _saveCompleted = MutableStateFlow(false)
    val saveCompleted: StateFlow<Boolean> = _saveCompleted.asStateFlow()
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()
    private val _maintenanceError = MutableStateFlow<String?>(null)
    val maintenanceError: StateFlow<String?> = _maintenanceError.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    val reminders = dao.observeReminders()
        .catch { _loadError.value = "Unable to load reminders. Please reopen the app to retry." }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val alarmUiState = coordinator.observeState()
        .catch { _maintenanceError.value = "Unable to read active alarms. Reopen the app to retry." }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmUiState())

    fun dismissAlarm(occurrence: AlarmOccurrence) {
        maintainReminder("Unable to dismiss alarm. Please try again.") {
            coordinator.dismiss(occurrence.reminderId, occurrence.test, occurrence.token)
            _alarmStatus.value = "Alarm dismissed. Normal dose times are unchanged."
        }
    }

    fun cancelPendingTest(occurrence: AlarmOccurrence) {
        maintainReminder("Unable to cancel test. Please try again.") {
            _alarmStatus.value = coordinator.cancelPendingTest(occurrence.reminderId, occurrence.token)
        }
    }

    fun synchronizeAlarms() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _alarmStatus.value = coordinator.synchronize()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _alarmStatus.value = "Unable to schedule alarms. Reopen the app to retry."
            }
        }
    }

    fun testReminder(id: Long) {
        maintainReminder("Unable to schedule a test alarm. Please try again.") {
            _alarmStatus.value = coordinator.testInOneMinute(id)
        }
    }

    fun testEscalation(id: Long) {
        maintainReminder("Unable to schedule an escalation test. Please try again.") {
            _alarmStatus.value = coordinator.testEscalation(id)
        }
    }

    fun clearSaveError() {
        _saveError.value = null
    }

    fun deleteReminder(id: Long) {
        maintainReminder("Unable to delete reminder. Please try again.") {
            _alarmStatus.value = coordinator.delete(id)
        }
    }

    fun setReminderEnabled(id: Long, enabled: Boolean) {
        maintainReminder("Unable to change reminder state. Please try again.") {
            _alarmStatus.value = coordinator.setEnabled(id, enabled)
        }
    }

    private fun maintainReminder(errorMessage: String, operation: suspend () -> Unit) {
        if (_isSaving.value) return
        _isSaving.value = true
        _maintenanceError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _maintenanceError.value = errorMessage
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun consumeSaveCompleted() {
        _saveCompleted.value = false
    }

    fun saveReminder(reminder: Reminder) {
        if (_isSaving.value) return
        _isSaving.value = true
        _saveError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _alarmStatus.value = coordinator.save(reminder)
                _saveCompleted.value = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _saveError.value = "Unable to save reminder. Please try again."
            } finally {
                _isSaving.value = false
            }
        }
    }
}
