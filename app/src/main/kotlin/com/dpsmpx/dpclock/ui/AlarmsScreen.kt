package com.dpsmpx.dpclock.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.QuickAction
import com.dpsmpx.dpclock.QuickRequest
import com.dpsmpx.dpclock.model.Alarm
import com.dpsmpx.dpclock.model.AlarmGroup
import com.dpsmpx.dpclock.model.AlarmRules
import com.dpsmpx.dpclock.model.AlarmTemplate
import com.dpsmpx.dpclock.model.BedtimeMode
import com.dpsmpx.dpclock.model.Exceptions
import com.dpsmpx.dpclock.model.Repeat
import com.dpsmpx.dpclock.model.RepeatKind
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.SleepRules
import com.dpsmpx.dpclock.model.days
import com.dpsmpx.dpclock.model.isRepeating
import com.dpsmpx.dpclock.model.kind
import com.dpsmpx.dpclock.persist
import com.dpsmpx.dpclock.persistLater
import com.dpsmpx.dpclock.platform.TimeText
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** «Если лечь сейчас» — когда до будильника не больше стольких часов. */
private const val SLEEP_HINT_MILLIS = 16 * 3_600_000L

/** Окно раздела «Будильники», которое открывает меню верхней панели (D-014). */
enum class AlarmsDialog { NEW_GROUP, FROM_TEMPLATE }

/** Окно действия с группой. */
private enum class GroupAction { RENAME, SAVE_TEMPLATE, DELETE_WITH_ALARMS }

/** Раздел «Будильники» — стартовый (SCREEN_MAP.md, п. 1). */
@Composable
fun AlarmsScreen(
    snackbar: SnackbarHostState,
    requestNotifications: () -> Unit,
    openSettings: () -> Unit,
    dialog: AlarmsDialog?,
    onDialog: (AlarmsDialog?) -> Unit,
    quick: QuickRequest? = null,
    onQuickHandled: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val repository = DpClockApp.graph(context).repository
    val alarms by repository.alarms.collectAsStateWithLifecycle(null)
    val groups by repository.groups.collectAsStateWithLifecycle(emptyList())
    val templates by repository.templates.collectAsStateWithLifecycle(emptyList())
    val exceptions by repository.exceptions.collectAsStateWithLifecycle(Exceptions.NONE)
    val settings by repository.settings.collectAsStateWithLifecycle(Settings())
    val snoozed by repository.snoozed.collectAsStateWithLifecycle(emptyMap())
    val reliability = rememberReliability()
    val now = rememberNowMillis(15_000L)
    val zone = ZoneId.systemDefault()
    val scope = rememberCoroutineScope()

    var creating by rememberSaveable { mutableStateOf(false) }
    /** Новый будильник — в эту группу; null — без группы. */
    var creatingIn by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var groupAction by rememberSaveable { mutableStateOf<GroupAction?>(null) }
    var actionGroupId by rememberSaveable { mutableStateOf<String?>(null) }

    // Ярлык «Новый будильник» и команда ассистента «открыть, чтобы поправить» (D-020).
    LaunchedEffect(quick?.token) {
        val request = quick ?: return@LaunchedEffect
        when (request.action) {
            QuickAction.NEW_ALARM -> {
                creatingIn = null
                creating = true
            }
            QuickAction.EDIT_ALARM -> editingId = request.alarmId
            else -> Unit
        }
        onQuickHandled(request.token)
    }

    fun announce(at: Long) {
        if (at > 0) scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(resources.getString(R.string.rings_in, untilText(resources, at - System.currentTimeMillis())))
        }
    }

    val list = alarms ?: return
    val nowInstant = Instant.ofEpochMilli(now)
    val sorted = AlarmRules.sorted(list, settings.alarmOrder, nowInstant, zone, exceptions)
    val groupIds = groups.map { it.id }.toSet()

    @Composable
    fun AlarmItem(alarm: Alarm) {
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
                exceptions = exceptions,
                now = nowInstant,
                onClick = { editingId = alarm.id },
                onToggle = { enabled ->
                    val at = AlarmRules.nextTrigger(alarm, Instant.now(), zone, exceptions)
                    if (enabled && at == null && !alarm.repeat.isRepeating) {
                        // Дата прошла — включить нечего, нужна новая дата.
                        editingId = alarm.id
                    } else {
                        scope.launch {
                            persist { repository.setAlarmEnabled(alarm.id, enabled) }
                            if (enabled && at != null) announce(at.toEpochMilli())
                        }
                    }
                },
            )
        }
    }

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
                val nearest = AlarmRules.nearest(list, nowInstant, zone, exceptions)
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
                // Время сна включено и будильник скоро — сколько выйдет сна (D-017, п. 2).
                if (nearest != null && settings.bedtime.mode != BedtimeMode.OFF && nearest.toEpochMilli() - now <= SLEEP_HINT_MILLIS) {
                    Text(
                        stringResource(
                            R.string.sleep_if_now,
                            durationText(resources, SleepRules.sleepIfNow(nowInstant, nearest)),
                            sleepText(settings.bedtime.sleepMinutes),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (list.isEmpty() && groups.isEmpty()) {
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
            items(sorted.filter { it.groupId == null || it.groupId !in groupIds }, key = { it.id }) { AlarmItem(it) }
            for (group in groups) {
                val members = sorted.filter { it.groupId == group.id }
                item(key = "group:" + group.id) {
                    GroupHeader(
                        group = group,
                        alarms = members,
                        onAdd = {
                            creatingIn = group.id
                            creating = true
                        },
                        onToggle = { enabled -> scope.launch { announce(persist { repository.setGroupEnabled(group.id, enabled) }) } },
                        onHonorExceptions = { honor -> persistLater { repository.setGroupHonorExceptions(group.id, honor) } },
                        onAction = { action ->
                            actionGroupId = group.id
                            groupAction = action
                        },
                        onUngroup = { persistLater { repository.deleteGroup(group.id, withAlarms = false) } },
                    )
                }
                if (members.isEmpty()) {
                    item(key = "group-empty:" + group.id) {
                        Text(
                            stringResource(R.string.group_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                items(members, key = { it.id }) { AlarmItem(it) }
            }
        }
        FloatingActionButton(
            onClick = {
                creatingIn = null
                creating = true
            },
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
                val group = creatingIn
                scope.launch { announce(persist { repository.addAlarm(hour, minute, group) }) }
            },
        )
    }
    val editing = list.firstOrNull { it.id == editingId }
    if (editing != null) {
        AlarmEditor(
            alarm = editing,
            settings = settings,
            groups = groups,
            exceptions = exceptions,
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

    when (dialog) {
        AlarmsDialog.NEW_GROUP -> NameDialog(
            title = stringResource(R.string.new_group),
            initial = "",
            onDismiss = { onDialog(null) },
            onConfirm = { name ->
                onDialog(null)
                persistLater { repository.createGroup(name) }
            },
        )
        AlarmsDialog.FROM_TEMPLATE -> TemplatesDialog(
            templates = templates,
            onDismiss = { onDialog(null) },
            onApply = { template ->
                onDialog(null)
                requestNotifications()
                scope.launch { announce(persist { repository.applyTemplate(template.id) }) }
            },
            onDelete = { template -> persistLater { repository.deleteTemplate(template.id) } },
        )
        null -> Unit
    }

    val actionGroup = groups.firstOrNull { it.id == actionGroupId }
    if (actionGroup != null) {
        val close = { groupAction = null }
        when (groupAction) {
            GroupAction.RENAME -> NameDialog(
                title = stringResource(R.string.group_rename),
                initial = actionGroup.name,
                onDismiss = close,
                onConfirm = { name ->
                    close()
                    persistLater { repository.renameGroup(actionGroup.id, name) }
                },
            )
            GroupAction.SAVE_TEMPLATE -> NameDialog(
                title = stringResource(R.string.save_as_template),
                initial = actionGroup.name,
                onDismiss = close,
                onConfirm = { name ->
                    close()
                    scope.launch {
                        persist { repository.saveTemplate(actionGroup.id, name) }
                        snackbar.showSnackbar(resources.getString(R.string.template_saved, name))
                    }
                },
            )
            GroupAction.DELETE_WITH_ALARMS -> AlertDialog(
                onDismissRequest = close,
                title = { Text(stringResource(R.string.group_delete_with_alarms_title, actionGroup.name)) },
                text = { Text(stringResource(R.string.group_delete_with_alarms_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        close()
                        persistLater { repository.deleteGroup(actionGroup.id, withAlarms = true) }
                    }) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
            )
            null -> Unit
        }
    }
}

/** Меню «Будильников» в верхней панели: исключения, новая группа, шаблоны (D-014). */
@Composable
fun AlarmsMenu(openExceptions: () -> Unit, onDialog: (AlarmsDialog) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(AppIcons.More, contentDescription = stringResource(R.string.more)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text(stringResource(R.string.exceptions_title)) }, onClick = {
            open = false
            openExceptions()
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.new_group)) }, onClick = {
            open = false
            onDialog(AlarmsDialog.NEW_GROUP)
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.from_template)) }, onClick = {
            open = false
            onDialog(AlarmsDialog.FROM_TEMPLATE)
        })
    }
}

@Composable
private fun GroupHeader(
    group: AlarmGroup,
    alarms: List<Alarm>,
    onAdd: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onHonorExceptions: (Boolean) -> Unit,
    onAction: (GroupAction) -> Unit,
    onUngroup: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pluralStringResource(R.plurals.alarms_count, alarms.size, alarms.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onAdd) { Icon(AppIcons.Add, contentDescription = stringResource(R.string.new_alarm_in_group, group.name)) }
        Switch(checked = alarms.any { it.enabled }, enabled = alarms.isNotEmpty(), onCheckedChange = onToggle)
        Box {
            IconButton(onClick = { menu = true }) { Icon(AppIcons.More, contentDescription = stringResource(R.string.more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val close = { menu = false }
                MenuItem(R.string.group_rename, close) { onAction(GroupAction.RENAME) }
                if (alarms.isNotEmpty()) {
                    MenuItem(R.string.save_as_template, close) { onAction(GroupAction.SAVE_TEMPLATE) }
                    MenuItem(R.string.group_honor_exceptions, close) { onHonorExceptions(true) }
                    MenuItem(R.string.group_ignore_exceptions, close) { onHonorExceptions(false) }
                }
                MenuItem(R.string.group_ungroup, close, onUngroup)
                if (alarms.isNotEmpty()) MenuItem(R.string.group_delete_with_alarms, close) { onAction(GroupAction.DELETE_WITH_ALARMS) }
            }
        }
    }
}

@Composable
private fun MenuItem(text: Int, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(text)) }, onClick = {
        close()
        action()
    })
}

@Composable
private fun TemplatesDialog(
    templates: List<AlarmTemplate>,
    onDismiss: () -> Unit,
    onApply: (AlarmTemplate) -> Unit,
    onDelete: (AlarmTemplate) -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.from_template)) },
        text = {
            if (templates.isEmpty()) {
                Text(stringResource(R.string.templates_empty))
            } else {
                LazyColumn {
                    items(templates, key = { it.id }) { template ->
                        ListItem(
                            headlineContent = { Text(template.name) },
                            supportingContent = {
                                Text(template.alarms.joinToString(", ") { TimeText.time(context, it.hour, it.minute) })
                            },
                            trailingContent = {
                                IconButton(onClick = { onDelete(template) }) {
                                    Icon(AppIcons.Delete, contentDescription = stringResource(R.string.delete))
                                }
                            },
                            modifier = Modifier.clickable { onApply(template) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Ввод названия: группы, шаблона, периода. */
@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(AlarmGroup.MAX_NAME) },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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
private fun AlarmRow(
    alarm: Alarm,
    snoozedUntil: Long?,
    exceptions: Exceptions,
    now: Instant,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val next = if (alarm.enabled) AlarmRules.nextTrigger(alarm, now, zone, exceptions) else null
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (alarm.enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (alarm.label.isNotBlank()) {
                    Text(alarm.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // У сменного графика своё время у каждого дня — крупно время ближайшего.
                val shown = if (alarm.repeat is Repeat.Cycle && next != null) TimeText.time(context, next.toEpochMilli())
                else TimeText.time(context, alarm.hour, alarm.minute)
                Text(
                    shown,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = if (alarm.enabled) FontWeight.Medium else FontWeight.Normal,
                )
                Text(repeatText(context, alarm.repeat), style = MaterialTheme.typography.bodyMedium)
                val skipDate = alarm.skipDate
                if (alarm.enabled && skipDate != null && alarm.repeat.isRepeating) {
                    Note(stringResource(R.string.skipped_on, TimeText.date(context, skipDate)))
                }
                if (alarm.enabled && alarm.repeat.isRepeating && alarm.honorExceptions) {
                    val plain = AlarmRules.nextTrigger(alarm, now, zone)
                    if (next != plain) {
                        Note(
                            if (next == null) stringResource(R.string.all_dates_excepted)
                            else stringResource(R.string.next_after_exceptions, TimeText.date(context, next.atZone(zone).toLocalDate())),
                        )
                    }
                }
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

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
}

/** Дни недели в порядке, принятом для языка: с понедельника или с воскресенья. */
fun orderedDays(locale: Locale): List<DayOfWeek> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return (0L until 7L).map { first.plus(it) }
}

fun repeatText(context: Context, repeat: Repeat): String {
    val resources = context.resources
    val locale = resources.configuration.locales[0]
    return when (repeat.kind) {
        RepeatKind.ONCE -> resources.getString(R.string.repeat_once)
        RepeatKind.DAILY -> resources.getString(R.string.repeat_daily)
        RepeatKind.WEEKDAYS -> resources.getString(R.string.repeat_weekdays)
        RepeatKind.DAYS -> orderedDays(locale).filter { it in repeat.days }
            .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
        RepeatKind.DATE -> TimeText.date(context, (repeat as Repeat.OnDate).date)
        RepeatKind.CYCLE -> resources.getString(R.string.repeat_cycle_days, (repeat as Repeat.Cycle).days.size)
        RepeatKind.WORKDAYS -> resources.getString(R.string.repeat_workdays)
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
