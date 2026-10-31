package com.dpsmpx.dpclock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.Alarm
import java.time.DayOfWeek

/** Готовые «Вздремнуть», мин (D-029, п. 3). */
val NAP_CHOICES = listOf(10, 20, 30, 45, 60)

/**
 * Новый будильник одним окном (D-029, п. 1): время, дни недели и название.
 * Без дней — однократный; остальное — в редакторе.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAlarmDialog(onDismiss: () -> Unit, onCreate: (hour: Int, minute: Int, days: Set<DayOfWeek>, label: String) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(initialHour = 7, initialMinute = 0, is24Hour = android.text.format.DateFormat.is24HourFormat(context))
    var typing by rememberSaveable { mutableStateOf(false) }
    var days by rememberSaveable { mutableStateOf(emptyList<DayOfWeek>()) }
    var label by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.new_alarm), modifier = Modifier.weight(1f))
                IconButton(onClick = { typing = !typing }) {
                    Icon(
                        if (typing) AppIcons.Clock else AppIcons.Keyboard,
                        contentDescription = stringResource(if (typing) R.string.input_dial else R.string.input_keyboard),
                    )
                }
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (typing) TimeInput(state) else TimePicker(state)
                DayChips(days.toSet()) { day -> days = if (day in days) days - day else days + day }
                Text(
                    stringResource(if (days.isEmpty()) R.string.new_alarm_once else R.string.new_alarm_repeat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(Alarm.MAX_LABEL) },
                    label = { Text(stringResource(R.string.alarm_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(state.hour, state.minute, days.toSet(), label.trim()) }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
