package com.EDanaII.pillreminder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.EDanaII.pillreminder.ui.theme.PillReminderTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.ui.platform.LocalContext
import java.util.Calendar
import java.text.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement

class MainActivity : ComponentActivity() {
    private lateinit var reminderViewModel: ReminderViewModel

    override fun onResume() {
        super.onResume()
        reminderViewModel.synchronizeAlarms()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        reminderViewModel = ViewModelProvider(this)[ReminderViewModel::class.java]
        setContent {
            PillReminderTheme {
                var showAddScreen by rememberSaveable { mutableStateOf(false) }
                var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
                val reminders by reminderViewModel.reminders.collectAsStateWithLifecycle()
                val alarmStatus by reminderViewModel.alarmStatus.collectAsStateWithLifecycle()
                val alarmUiState by reminderViewModel.alarmUiState.collectAsStateWithLifecycle()
                val alarmForeground = rememberAlarmForeground()
                val isSaving by reminderViewModel.isSaving.collectAsStateWithLifecycle()
                val saveError by reminderViewModel.saveError.collectAsStateWithLifecycle()
                val loadError by reminderViewModel.loadError.collectAsStateWithLifecycle()
                val maintenanceError by reminderViewModel.maintenanceError.collectAsStateWithLifecycle()
                val saveCompleted by reminderViewModel.saveCompleted.collectAsStateWithLifecycle()
                LaunchedEffect(saveCompleted) {
                    if (saveCompleted) {
                        showAddScreen = false
                        editingId = null
                        reminderViewModel.consumeSaveCompleted()
                    }
                }

                BackHandler(enabled = showAddScreen) {
                    if (!isSaving) {
                        showAddScreen = false
                        editingId = null
                    }
                }

                ForegroundAlarmHost(
                    alarms = alarmUiState.activeAlarms,
                    foreground = alarmForeground,
                    isWorking = isSaving,
                    onDismiss = reminderViewModel::dismissAlarm
                ) { onOpenAlarm ->
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        if (showAddScreen) {
                            val initialReminder = reminders.firstOrNull { it.id == editingId }
                            if (editingId != null && initialReminder == null) {
                                Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
                                    Text(loadError ?: "Loading reminder…")
                                    Button(onClick = {
                                        showAddScreen = false
                                        editingId = null
                                    }) { Text("Cancel") }
                                }
                            } else {
                                key(editingId) {
                                    AddReminderScreen(
                                        modifier = Modifier.padding(innerPadding),
                                        onBack = {
                                            showAddScreen = false
                                            editingId = null
                                        },
                                        onSave = reminderViewModel::saveReminder,
                                        isSaving = isSaving,
                                        saveError = saveError,
                                        initialReminder = initialReminder
                                    )
                                }
                            }
                        } else {
                            ReminderListScreen(
                                modifier = Modifier.padding(innerPadding),
                                onAdd = {
                                    reminderViewModel.clearSaveError()
                                    editingId = null
                                    showAddScreen = true
                                },
                                reminders = reminders,
                                loadError = loadError,
                                maintenanceError = maintenanceError,
                                isWorking = isSaving,
                                onEdit = { reminder ->
                                    reminderViewModel.clearSaveError()
                                    editingId = reminder.id
                                    showAddScreen = true
                                },
                                onDelete = reminderViewModel::deleteReminder,
                                onEnabledChange = reminderViewModel::setReminderEnabled,
                                permissionsContent = { AlarmPermissionsSection(this@MainActivity) },
                                alarmStatus = alarmStatus,
                                onTest = reminderViewModel::testReminder,
                                onTestEscalation = reminderViewModel::testEscalation,
                                alarmUiState = alarmUiState,
                                onOpenAlarm = onOpenAlarm,
                                onDismissAlarm = reminderViewModel::dismissAlarm,
                                onCancelTest = reminderViewModel::cancelPendingTest
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReminderListScreen(
    modifier: Modifier = Modifier,
    onAdd: () -> Unit = {},
    reminders: List<Reminder> = emptyList(),
    loadError: String? = null,
    maintenanceError: String? = null,
    isWorking: Boolean = false,
    onEdit: (Reminder) -> Unit = {},
    onDelete: (Long) -> Unit = {},
    onEnabledChange: (Long, Boolean) -> Unit = { _, _ -> },
    permissionsContent: @Composable () -> Unit = {},
    alarmStatus: String? = null,
    onTest: (Long) -> Unit = {},
    onTestEscalation: (Long) -> Unit = {},
    alarmUiState: AlarmUiState = AlarmUiState(),
    onOpenAlarm: (AlarmOccurrence) -> Unit = {},
    onDismissAlarm: (AlarmOccurrence) -> Unit = {},
    onCancelTest: (AlarmOccurrence) -> Unit = {}
) {
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val reminderToDelete = reminders.firstOrNull { it.id == deleteId }
    if (reminderToDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Delete reminder?") },
            text = { Text("Permanently delete “${reminderToDelete.name}”?") },
            confirmButton = {
                TextButton(
                    enabled = !isWorking,
                    onClick = {
                        onDelete(reminderToDelete.id)
                        deleteId = null
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteId = null }) { Text("Cancel") }
            }
        )
    }
    Column(
        modifier = modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onAdd, enabled = !isWorking) {
            Text("Add Reminder")
        }

        alarmUiState.activeAlarms.forEach { alarm ->
            Spacer(Modifier.height(16.dp))
            Text(if (alarm.occurrence.test) "Active test alarm" else "Active medication alarm",
                style = MaterialTheme.typography.titleLarge)
            Text(alarm.reminder.name)
            Text(alarm.reminder.instructions)
            Button(onClick = { onOpenAlarm(alarm.occurrence) }, enabled = !isWorking) {
                Text("Open alarm")
            }
            Button(onClick = { onDismissAlarm(alarm.occurrence) }, enabled = !isWorking) {
                Text("Dismiss alarm")
            }
        }

        permissionsContent()
        if (alarmStatus != null) Text(alarmStatus)

        if (maintenanceError != null) {
            Text(maintenanceError, color = MaterialTheme.colorScheme.error)
        }
        if (loadError != null) {
            Text(loadError, color = MaterialTheme.colorScheme.error)
        } else if (reminders.isEmpty()) {
            Text("No reminders configured")
        } else {
            reminders.forEach { reminder ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(reminder.name, style = MaterialTheme.typography.titleMedium)
                Text(reminder.instructions, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (reminder.enabled) "Enabled" else "Disabled",
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = reminder.enabled,
                        onCheckedChange = { onEnabledChange(reminder.id, it) },
                        enabled = !isWorking
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onEdit(reminder) }, enabled = !isWorking) {
                        Text("Edit")
                    }
                    TextButton(onClick = { deleteId = reminder.id }, enabled = !isWorking) {
                        Text("Delete")
                    }
                }
                val nextDose = alarmUiState.pendingDoses.firstOrNull { it.reminderId == reminder.id }
                if (reminder.enabled) {
                    Text(nextDose?.let {
                        "Next scheduled dose: " + DateFormat.getDateTimeInstance()
                            .format(java.util.Date(it.scheduledTime))
                    } ?: "No dose currently scheduled")
                }
                val pendingTest = alarmUiState.pendingTests.firstOrNull { it.reminderId == reminder.id }
                val testActive = alarmUiState.activeAlarms.any {
                    it.reminder.id == reminder.id && it.occurrence.test
                }
                if (pendingTest != null) {
                    Text("Test pending: " + DateFormat.getDateTimeInstance()
                        .format(java.util.Date(pendingTest.scheduledTime)))
                    TextButton(onClick = { onCancelTest(pendingTest) }, enabled = !isWorking) {
                        Text("Cancel pending test")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(modifier = Modifier.weight(1f), onClick = { onTest(reminder.id) },
                        enabled = reminder.enabled && !isWorking && !testActive) {
                        Text(when {
                            testActive -> "Test active — dismiss first"
                            pendingTest != null -> "Reschedule test in 1 minute"
                            else -> "Test in 1 minute"
                        })
                    }
                    TextButton(modifier = Modifier.weight(1f), onClick = { onTestEscalation(reminder.id) },
                        enabled = reminder.enabled && !isWorking && !testActive) {
                        Text("Test Escalation")
                    }
                }
            }
        }
    }
}

@Composable
fun AddReminderScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSave: (Reminder) -> Unit,
    isSaving: Boolean = false,
    saveError: String? = null,
    initialReminder: Reminder? = null
) {
    var reminderName by rememberSaveable(initialReminder?.id) {
        mutableStateOf(initialReminder?.name ?: "")
    }

    var medicationInstructions by rememberSaveable(initialReminder?.id) {
        mutableStateOf(initialReminder?.instructions ?: "")
    }

    var doseIntervalHours by rememberSaveable(initialReminder?.id) {
        mutableStateOf(initialReminder?.doseIntervalHours?.toString() ?: "8")
    }

    var nagIntervalMinutes by rememberSaveable(initialReminder?.id) {
        mutableStateOf(initialReminder?.nagIntervalMinutes?.toString() ?: "15")
    }
    var reminderEnabled by rememberSaveable(initialReminder?.id) {
        mutableStateOf(initialReminder?.enabled ?: true)
    }
    var alarmSoundUri by rememberSaveable(initialReminder?.id) { mutableStateOf(initialReminder?.alarmSoundUri) }
    var vibrationEnabled by rememberSaveable(initialReminder?.id) { mutableStateOf(initialReminder?.vibrationEnabled ?: true) }
    var escalationEnabled by rememberSaveable(initialReminder?.id) { mutableStateOf(initialReminder?.escalationEnabled ?: true) }

    val context = LocalContext.current

    var startTimeMillis by rememberSaveable(initialReminder?.id) {
        mutableStateOf(initialReminder?.startTimeMillis ?: System.currentTimeMillis())
    }

    val selectedCalendar = Calendar.getInstance().apply {
        timeInMillis = startTimeMillis
    }

    val dateText = DateFormat.getDateInstance(
        DateFormat.MEDIUM
    ).format(selectedCalendar.time)

    val timeText = DateFormat.getTimeInstance(
        DateFormat.SHORT
    ).format(selectedCalendar.time)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = if (initialReminder == null) "Add Reminder" else "Edit Reminder",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = reminderName,
            onValueChange = { reminderName = it },
            label = { Text("Reminder Name") },
            placeholder = { Text("e.g. Morning Pills") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = medicationInstructions,
            onValueChange = { medicationInstructions = it },
            label = { Text("Medication Instructions") },
            placeholder = {
                Text("e.g. Phenobarbital — 2 tablets\nKeppra — 1 tablet")
            },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = doseIntervalHours,
            onValueChange = { doseIntervalHours = it },
            label = { Text("Dose Interval (hours)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = nagIntervalMinutes,
            onValueChange = { nagIntervalMinutes = it },
            label = { Text("Nag Interval (minutes)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Spacer(modifier = Modifier.height(24.dp))

        AlarmAlertSettings(alarmSoundUri, vibrationEnabled, escalationEnabled, !isSaving,
            { alarmSoundUri = it }, { vibrationEnabled = it }, { escalationEnabled = it })
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Starting Date and Time",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    DatePickerDialog(
                        context,
                        { _, year, month, day ->
                            val calendar = Calendar.getInstance().apply {
                                timeInMillis = startTimeMillis
                                set(year, month, day)
                            }
                            startTimeMillis = calendar.timeInMillis
                        },
                        selectedCalendar.get(Calendar.YEAR),
                        selectedCalendar.get(Calendar.MONTH),
                        selectedCalendar.get(Calendar.DAY_OF_MONTH)
                    ).show()
                }
            ) {
                Text("Date: $dateText")
            }

            Button(
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            val calendar = Calendar.getInstance().apply {
                                timeInMillis = startTimeMillis
                                set(Calendar.HOUR_OF_DAY, hour)
                                set(Calendar.MINUTE, minute)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                            startTimeMillis = calendar.timeInMillis
                        },
                        selectedCalendar.get(Calendar.HOUR_OF_DAY),
                        selectedCalendar.get(Calendar.MINUTE),
                        false
                    ).show()
                }
            ) {
                Text("Time: $timeText")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (reminderEnabled) "Enabled" else "Disabled", modifier = Modifier.weight(1f))
            Switch(
                checked = reminderEnabled,
                onCheckedChange = { reminderEnabled = it },
                enabled = !isSaving
            )
        }

        Button(onClick = onBack, enabled = !isSaving) {
            Text("Cancel")
        }

        Button(
            onClick = {
                onSave(
                    Reminder(
                        id = initialReminder?.id ?: 0,
                        name = reminderName.trim(),
                        instructions = medicationInstructions.trim(),
                        startTimeMillis = startTimeMillis,
                        doseIntervalHours = doseIntervalHours.toInt(),
                        nagIntervalMinutes = nagIntervalMinutes.toInt(),
                        enabled = reminderEnabled,
                        alarmSoundUri = alarmSoundUri,
                        vibrationEnabled = vibrationEnabled,
                        escalationEnabled = escalationEnabled
                    )
                )
            },
            enabled = !isSaving && reminderName.isNotBlank() &&
                    medicationInstructions.isNotBlank() &&
                    (doseIntervalHours.toIntOrNull() ?: 0) > 0 &&
                    (nagIntervalMinutes.toIntOrNull() ?: 0) > 0
        ) {
            Text(if (isSaving) "Saving…" else "Save Reminder")
        }

        if (saveError != null) {
            Text(saveError, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    PillReminderTheme {
        ReminderListScreen()
    }
}
