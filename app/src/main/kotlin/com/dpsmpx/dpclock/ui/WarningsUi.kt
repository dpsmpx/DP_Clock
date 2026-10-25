package com.dpsmpx.dpclock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.Warnings

/**
 * Предупреждения до сигнала (D-025, п. 5): готовые — за день, час, 30 и 5 минут —
 * и своё время в днях, часах и минутах; несколько сразу.
 */
@Composable
fun WarningsDialog(title: String, initial: List<Int>, onDismiss: () -> Unit, onDone: (List<Int>) -> Unit) {
    val context = LocalContext.current
    var list by remember { mutableStateOf(Warnings.normalize(initial)) }
    var custom by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    val customMinutes = (days.toIntOrNull() ?: 0) * 24 * 60 + (hours.toIntOrNull() ?: 0) * 60 + (minutes.toIntOrNull() ?: 0)
    val full = list.size >= Warnings.MAX

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { TitleText(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.warnings_hint), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in (Warnings.CHOICES + list).distinct().sortedDescending()) {
                        val on = choice in list
                        FilterChip(
                            selected = on,
                            enabled = on || !full,
                            onClick = { list = Warnings.normalize(if (on) list - choice else list + choice) },
                            label = { Text(stringResource(R.string.warning_before, leadText(context, choice))) },
                        )
                    }
                }
                if (custom) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        NumberField(days, stringResource(R.string.warnings_days), Modifier.weight(1f)) { days = it }
                        NumberField(hours, stringResource(R.string.warnings_hours), Modifier.weight(1f)) { hours = it }
                        NumberField(minutes, stringResource(R.string.warnings_minutes), Modifier.weight(1f)) { minutes = it }
                    }
                    val valid = customMinutes in 1..Warnings.MAX_MINUTES
                    if (customMinutes > Warnings.MAX_MINUTES) {
                        Text(stringResource(R.string.warnings_too_far), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(enabled = valid && !full, onClick = {
                        list = Warnings.normalize(list + customMinutes)
                        days = ""
                        hours = ""
                        minutes = ""
                        custom = false
                    }) { Text(stringResource(R.string.warnings_add)) }
                } else {
                    TextButton(enabled = !full, onClick = { custom = true }) { Text(stringResource(R.string.warnings_custom)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(list) }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NumberField(value: String, label: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() }.take(3)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}
