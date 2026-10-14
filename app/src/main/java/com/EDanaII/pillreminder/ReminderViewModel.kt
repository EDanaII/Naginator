package com.EDanaII.pillreminder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReminderViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = ReminderDatabase.getInstance(application).reminderDao()
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

    fun clearSaveError() {
        _saveError.value = null
    }

    fun deleteReminder(id: Long) {
        maintainReminder("Unable to delete reminder. Please try again.") {
            check(dao.delete(id) == 1)
        }
    }

    fun setReminderEnabled(id: Long, enabled: Boolean) {
        maintainReminder("Unable to change reminder state. Please try again.") {
            check(dao.setEnabled(id, enabled) == 1)
        }
    }

    private fun maintainReminder(errorMessage: String, operation: suspend () -> Unit) {
        if (_isSaving.value) return
        _isSaving.value = true
        _maintenanceError.value = null
        viewModelScope.launch {
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
        viewModelScope.launch {
            try {
                if (reminder.id == 0L) {
                    dao.insert(reminder)
                } else {
                    check(dao.update(
                        reminder.id, reminder.name, reminder.instructions, reminder.startTimeMillis,
                        reminder.doseIntervalHours, reminder.nagIntervalMinutes, reminder.enabled
                    ) == 1)
                }
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
