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
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.EDanaII.pillreminder.ui.theme.PillReminderTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PillReminderTheme {
                var showAddScreen by remember { mutableStateOf(false) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (showAddScreen) {
                        AddReminderScreen(
                            modifier = Modifier.padding(innerPadding),
                            onBack = { showAddScreen = false }
                        )
                    } else {
                        ReminderListScreen(
                            modifier = Modifier.padding(innerPadding),
                            onAdd = { showAddScreen = true }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReminderListScreen(
    modifier: Modifier = Modifier,
    onAdd: () -> Unit = {}
) {
    Column(
        modifier = modifier.padding(16.dp)
    ) {
        Text(
            text = "Pill Reminder",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onAdd) {
            Text("Add Reminder")
        }

        Text(
            text = "No reminders configured"
        )
    }
}

@Composable
fun AddReminderScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    var reminderName by rememberSaveable {
        mutableStateOf("")
    }

    var medicationInstructions by rememberSaveable {
        mutableStateOf("")
    }

    var doseIntervalHours by rememberSaveable {
        mutableStateOf("8")
    }

    var nagIntervalMinutes by rememberSaveable {
        mutableStateOf("15")
    }

    val context = LocalContext.current

    var startTimeMillis by rememberSaveable {
        mutableStateOf(System.currentTimeMillis())
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
            text = "Add Reminder",
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

        Button(onClick = onBack) {
            Text("Back")
        }

        Button(
            onClick = {
                // Saving comes next
            },
            enabled = reminderName.isNotBlank() &&
                    medicationInstructions.isNotBlank() &&
                    (doseIntervalHours.toIntOrNull() ?: 0) > 0 &&
                    (nagIntervalMinutes.toIntOrNull() ?: 0) > 0
        ) {
            Text("Save Reminder")
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