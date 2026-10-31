package com.dpsmpx.dpclock.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.Pomodoro
import com.dpsmpx.dpclock.model.TimerProgram
import com.dpsmpx.dpclock.platform.FocusMode
import com.dpsmpx.dpclock.platform.TimeText

private val WORK_CHOICES = listOf(5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 75, 90, 120, 150, 180)
private val SHORT_CHOICES = (1..15).toList() + listOf(20, 25, 30)
private val LONG_CHOICES = listOf(5, 10, 15, 20, 25, 30, 40, 45, 60, 90, 120)

/** Промежутки «каждые N секунд» (D-025, п. 4), с. */
private val INTERVAL_CHOICES = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 300, 600)

/**
 * Помидор (D-025, п. 1): длины работы и перерывов, помидоров до длинного,
 * подходов; «Сам переходит дальше» и «Не беспокоить» на работу — по желанию.
 */
@Composable
fun PomodoroDialog(initial: Pomodoro, onDismiss: () -> Unit, onStart: (Pomodoro) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var pomodoro by remember { mutableStateOf(initial) }
    var access by remember { mutableStateOf(FocusMode.hasAccess(context)) }
    // Доступ к «Не беспокоить» выдают в настройках Android — после возврата перечитать.
    LifecycleResumeEffect(Unit) {
        access = FocusMode.hasAccess(context)
        onPauseOrDispose { }
    }
    fun minutes(value: Int) = resources.getString(R.string.minutes_value, value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { TitleText(stringResource(R.string.pomodoro)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChoiceStepper(stringResource(R.string.pomodoro_work), WORK_CHOICES.withValue(pomodoro.workMinutes), pomodoro.workMinutes, ::minutes) {
                    pomodoro = pomodoro.copy(workMinutes = it)
                }
                ChoiceStepper(stringResource(R.string.pomodoro_short), SHORT_CHOICES.withValue(pomodoro.shortMinutes), pomodoro.shortMinutes, ::minutes) {
                    pomodoro = pomodoro.copy(shortMinutes = it)
                }
                ChoiceStepper(stringResource(R.string.pomodoro_long), LONG_CHOICES.withValue(pomodoro.longMinutes), pomodoro.longMinutes, ::minutes) {
                    pomodoro = pomodoro.copy(longMinutes = it)
                }
                ChoiceStepper(stringResource(R.string.pomodoro_long_every), (1..Pomodoro.MAX_LONG_EVERY).toList(), pomodoro.longEvery, { it.toString() }) {
                    pomodoro = pomodoro.copy(longEvery = it)
                }
                ChoiceStepper(stringResource(R.string.pomodoro_sets), (1..MAX_SETS).toList(), pomodoro.sets.coerceAtMost(MAX_SETS), { it.toString() }) {
                    pomodoro = pomodoro.copy(sets = it)
                }
                SwitchRow(stringResource(R.string.pomodoro_auto), pomodoro.autoAdvance, stringResource(R.string.pomodoro_auto_text)) {
                    pomodoro = pomodoro.copy(autoAdvance = it)
                }
                SwitchRow(stringResource(R.string.pomodoro_dnd), pomodoro.dnd, stringResource(R.string.pomodoro_dnd_text)) {
                    pomodoro = pomodoro.copy(dnd = it)
                }
                if (pomodoro.dnd && !access) {
                    Text(stringResource(R.string.pomodoro_dnd_access), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                    }) { Text(stringResource(R.string.fix)) }
                }
                val total = pomodoro.program("", "", "").totalMillis
                Text(
                    stringResource(R.string.pomodoro_total, durationText(resources, total)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onStart(pomodoro) }) { Text(stringResource(R.string.start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Значение из файла может быть не из списка — в список его. */
private fun List<Int>.withValue(value: Int): List<Int> = (this + value).distinct().sorted()

private const val MAX_SETS = 10

/** Каждые N секунд (D-025, п. 4): короткий сигнал через равные промежутки — N раз или пока не остановят. */
@Composable
fun IntervalDialog(onDismiss: () -> Unit, onStart: (TimerProgram, String) -> Unit) {
    val resources = LocalResources.current
    var seconds by rememberSaveable { mutableIntStateOf(30) }
    var forever by rememberSaveable { mutableStateOf(true) }
    var count by rememberSaveable { mutableIntStateOf(10) }
    fun secondsText(value: Int) = if (value < 60 || value % 60 != 0) {
        TimeText.duration(value * 1_000L)
    } else {
        resources.getString(R.string.minutes_value, value / 60)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { TitleText(stringResource(R.string.interval_timer)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.interval_text), style = MaterialTheme.typography.bodyMedium)
                ChoiceStepper(stringResource(R.string.interval_every), INTERVAL_CHOICES, seconds, ::secondsText) { seconds = it }
                SwitchRow(stringResource(R.string.interval_forever), forever) { forever = it }
                if (!forever) {
                    Stepper(
                        stringResource(R.string.interval_count),
                        count.toString(),
                        onMinus = if (count > 2) ({ count-- }) else null,
                        onPlus = if (count < TimerProgram.MAX_CYCLES) ({ count++ }) else null,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onStart(TimerProgram.interval(seconds * 1_000L, if (forever) null else count), resources.getString(R.string.interval_name, secondsText(seconds)))
            }) { Text(stringResource(R.string.start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
