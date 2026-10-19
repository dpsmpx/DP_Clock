package com.dpsmpx.dpclock.ui

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.persist
import com.dpsmpx.dpclock.persistLater
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.Alarm
import com.dpsmpx.dpclock.model.AlarmRules
import com.dpsmpx.dpclock.model.Repeat
import com.dpsmpx.dpclock.model.RepeatKind
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.days
import com.dpsmpx.dpclock.model.kind
import com.dpsmpx.dpclock.platform.TimeText
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Раздел «Будильники» — стартовый (SCREEN_MAP.md, п. 1). */
@Composable
fun AlarmsScreen(
    snackbar: SnackbarHostState,
    requestNotifications: () -> Unit,
    openSettings: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val repository = DpClockApp.graph(context).repository
    val alarms by repository.alarms.collectAsStateWithLifecycle(null)
    val settings by repository.settings.collectAsStateWithLifecycle(Settings())
    val snoozed by repository.snoozed.collectAsStateWithLifecycle(emptyMap())
    val reliability = rememberReliability()
    val now = rememberNowMillis(15_000L)
    val zone = ZoneId.systemDefault()
    val scope = rememberCoroutineScope()

    var creating by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    fun announce(at: Long) {
        if (at > 0) scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(resources.getString(R.string.rings_in, untilText(resources, at - System.currentTimeMillis())))
        }
    }

    val list = alarms ?: return
    val sorted = AlarmRules.sorted(list, settings.alarmOrder, Instant.ofEpochMilli(now), zone)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val issues = reliability.issues(settings.xiaomiHintsDone)
            if (issues.isNotEmpty()) {
                item(key = "issues") { ReliabilityBanner(issues, openSettings) }
            }
            item(key = "next") {
                val nearest = AlarmRules.nearest(list, Instant.ofEpochMilli(now), zone)
                Text(
                    text = if (nearest == null) {
                        stringResource(R.string.no_enabled_alarms)
                    } else {
                        stringResource(R.string.next_alarm_in, untilText(resources, nearest.toEpochMilli() - now))
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (list.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.alarms_empty), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.size(16.dp))
                        FilledTonalButton(onClick = { creating = true }) { Text(stringResource(R.string.new_alarm)) }
                    }
                }
            }
            items(sorted, key = { it.id }) { alarm ->
                SwipeToDelete(
                    onDelete = {
                        scope.launch {
                            val deleted = persist { repository.deleteAlarm(alarm.id) } ?: return@launch
                            snackbar.currentSnackbarData?.dismiss()
                            val result = snackbar.showSnackbar(
                                resources.getString(R.string.alarm_deleted),
                                actionLabel = resources.getString(R.string.undo),
                                duration = SnackbarDuration.Short,
                            )
                            if (result == SnackbarResult.ActionPerformed) persist { repository.restoreAlarm(deleted) }
                        }
                    },
                ) {
                    AlarmRow(
                        alarm = alarm,
                        snoozedUntil = snoozed[alarm.id],
                        onClick = { editingId = alarm.id },
                        onToggle = { enabled ->
                            scope.launch {
                                persist { repository.setAlarmEnabled(alarm.id, enabled) }
                                if (enabled) {
                                    val at = AlarmRules.nextTrigger(alarm, Instant.now(), ZoneId.systemDefault())
                                    announce(at.toEpochMilli())
                                }
                            }
                        },
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(AppIcons.Add, contentDescription = stringResource(R.string.new_alarm)) }
    }

    if (creating) {
        TimePickerDialog(
            initialHour = 7,
            initialMinute = 0,
            onDismiss = { creating = false },
            onConfirm = { hour, minute ->
                creating = false
                requestNotifications()
                scope.launch { announce(persist { repository.addAlarm(hour, minute) }) }
            },
        )
    }
    val editing = list.firstOrNull { it.id == editingId }
    if (editing != null) {
        AlarmEditor(
            alarm = editing,
            onDismiss = { editingId = null },
            onSave = { updated ->
                editingId = null
                scope.launch {
                    val at = persist { repository.saveAlarm(updated.copy(enabled = true)) }
                    announce(at)
                }
            },
            onDelete = {
                editingId = null
                persistLater { repository.deleteAlarm(editing.id) }
            },
        )
    }
}

@Composable
private fun ReliabilityBanner(issues: List<Issue>, openSettings: () -> Unit) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (issue in issues) {
                Text(stringResource(issue.title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(issue.text), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        if (issue == Issue.XIAOMI) openSettings() else context.openFix(issue)
                    }) { Text(stringResource(R.string.fix)) }
                }
            }
        }
    }
}

@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(AppIcons.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
            }
        },
        enableDismissFromStartToEnd = false,
        onDismiss = {
            onDelete()
            // Строка вернётся на место: удаление можно отменить.
            scope.launch { state.reset() }
        },
    ) { content() }
}

@Composable
private fun AlarmRow(alarm: Alarm, snoozedUntil: Long?, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (alarm.enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    TimeText.time(context, alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = if (alarm.enabled) FontWeight.Medium else FontWeight.Normal,
                )
                Text(repeatText(LocalResources.current, alarm.repeat), style = MaterialTheme.typography.bodyMedium)
                if (snoozedUntil != null) {
                    Text(
                        stringResource(R.string.snoozed_until, TimeText.time(context, snoozedUntil)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        }
    }
}

/** Дни недели в порядке, принятом для языка: с понедельника или с воскресенья. */
fun orderedDays(locale: Locale): List<DayOfWeek> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return (0L until 7L).map { first.plus(it) }
}

fun repeatText(resources: Resources, repeat: Repeat): String {
    val locale = resources.configuration.locales[0]
    return when (repeat.kind) {
        RepeatKind.ONCE -> resources.getString(R.string.repeat_once)
        RepeatKind.DAILY -> resources.getString(R.string.repeat_daily)
        RepeatKind.WEEKDAYS -> resources.getString(R.string.repeat_weekdays)
        RepeatKind.DAYS -> orderedDays(locale).filter { it in repeat.days }
            .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
    }
}

/** Редактор будильника MVP: время, дни повтора, удаление (SCREEN_MAP.md, п. 2). */
@Composable
private fun AlarmEditor(alarm: Alarm, onDismiss: () -> Unit, onSave: (Alarm) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var hour by rememberSaveable { mutableIntStateOf(alarm.hour) }
    var minute by rememberSaveable { mutableIntStateOf(alarm.minute) }
    var days by remember { mutableStateOf(alarm.repeat.days) }
    var pickTime by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_alarm)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { pickTime = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(TimeText.time(context, hour, minute), style = MaterialTheme.typography.displayMedium)
                }
                Text(stringResource(R.string.repeat), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (day in orderedDays(locale)) {
                        FilterChip(
                            selected = day in days,
                            onClick = { days = if (day in days) days - day else days + day },
                            label = { Text(day.getDisplayName(TextStyle.NARROW_STANDALONE, locale)) },
                        )
                    }
                }
                Text(
                    if (days.isEmpty()) stringResource(R.string.repeat_once) else repeatText(LocalResources.current, Repeat.of(days)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDelete) {
                    Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.delete))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(alarm.copy(hour = hour, minute = minute, repeat = Repeat.of(days))) }) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (pickTime) {
        TimePickerDialog(
            initialHour = hour,
            initialMinute = minute,
            onDismiss = { pickTime = false },
            onConfirm = { h, m ->
                hour = h
                minute = m
                pickTime = false
            },
        )
    }
}

/** Выбор времени: циферблат Material 3 с переключением на ввод цифр (D-010, п. 3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initialHour: Int, initialMinute: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = android.text.format.DateFormat.is24HourFormat(context),
    )
    var typing by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (typing) TimeInput(state) else TimePicker(state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { typing = !typing }) {
                    Icon(
                        if (typing) AppIcons.Clock else AppIcons.Keyboard,
                        contentDescription = stringResource(if (typing) R.string.input_dial else R.string.input_keyboard),
                    )
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
