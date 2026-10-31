package com.dpsmpx.dpclock.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.DayTask
import com.dpsmpx.dpclock.model.Task
import com.dpsmpx.dpclock.model.TaskCache
import com.dpsmpx.dpclock.model.TaskHistory
import com.dpsmpx.dpclock.model.TaskLevel
import com.dpsmpx.dpclock.model.TaskMark
import com.dpsmpx.dpclock.model.TaskRepeat
import com.dpsmpx.dpclock.model.TaskRules
import com.dpsmpx.dpclock.model.TaskSource
import com.dpsmpx.dpclock.model.TaskTree
import com.dpsmpx.dpclock.persist
import com.dpsmpx.dpclock.persistLater
import com.dpsmpx.dpclock.platform.GoogleSignIn
import com.dpsmpx.dpclock.platform.PhoneCalendarInfo
import com.dpsmpx.dpclock.platform.TaskCalendar
import com.dpsmpx.dpclock.platform.TimeText
import com.dpsmpx.dpclock.platform.dayTasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Сколько задач видно в «Часах» сразу; остальные — по «Ещё» и в «Все задачи». */
private const val CLOCK_ROWS = 3

@Composable
internal fun rememberToday(): LocalDate {
    val now = rememberNowMillis(60_000L)
    return Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
}

/**
 * Задачи на сегодня в «Часах» (D-032; выбор автора — в «Часах» и экран «Все задачи»):
 * свои и Google, сегодня и просроченные; «+» — новая задача, касание по своей —
 * правка, кружок — «сделано». Пока раздел виден, задачи Google обновляются не чаще
 * раза в 15 минут.
 */
@Composable
fun TasksPanel(active: Boolean, onBackdrop: Boolean, onOpenAll: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = DpClockApp.graph(context)
    val store = graph.tasks
    val state by store.state.collectAsStateWithLifecycle()
    val own by graph.repository.tasks.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(active) { if (active) persistLater { store.refresh() } }
    val cache = state.cache
    if (!cache.showClock) return
    val today = rememberToday()
    val tasks = dayTasks(own, cache, today)
    var expanded by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Task?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) tasks else tasks.take(CLOCK_ROWS)
    val text = if (onBackdrop) Color.White else MaterialTheme.colorScheme.onSurface
    val secondary = if (onBackdrop) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .then(if (onBackdrop) Modifier.background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(16.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tasks_today), style = MaterialTheme.typography.titleSmall, color = text, modifier = Modifier.weight(1f))
            IconButton(onClick = { creating = true }) { Icon(AppIcons.Add, contentDescription = stringResource(R.string.tasks_new), tint = text) }
            TextButton(onClick = onOpenAll) { Text(stringResource(R.string.tasks_all), color = text) }
        }
        if (tasks.isEmpty()) {
            Text(
                stringResource(if (state.refreshing) R.string.tasks_loading else R.string.tasks_none),
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
            )
        }
        for (task in shown) {
            DayTaskRow(
                task,
                today,
                text,
                secondary,
                onDone = { persistLater { failed = !complete(context, task) } },
                onOpen = { if (task.source == TaskSource.OWN) editing = own.firstOrNull { it.id == task.id } },
            )
        }
        if (tasks.size > shown.size) {
            TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.tasks_more, tasks.size - shown.size), color = text) }
        }
        if (failed) Text(stringResource(R.string.tasks_complete_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (cache.google && cache.failure != null) Text(stringResource(R.string.tasks_not_updated), style = MaterialTheme.typography.bodySmall, color = secondary)
    }
    if (creating) TaskEditor(null, today, onDismiss = { creating = false })
    editing?.let { task -> TaskEditor(task, today, onDismiss = { editing = null }) }
}

/** «Сделано»: своя — в базе, Google — в Google Tasks. */
internal suspend fun complete(context: Context, task: DayTask): Boolean {
    val graph = DpClockApp.graph(context)
    return when (task.source) {
        TaskSource.OWN -> {
            graph.repository.completeTask(task.id)
            true
        }
        TaskSource.GOOGLE -> graph.tasks.completeGoogle(task)
    }
}

@Composable
internal fun DayTaskRow(task: DayTask, today: LocalDate, text: Color, secondary: Color, onDone: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val overdue = TaskRules.overdue(task, today)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = task.source == TaskSource.OWN, onClick = onOpen),
    ) {
        IconButton(onClick = onDone) {
            Icon(AppIcons.TaskOpen, contentDescription = stringResource(R.string.tasks_mark_done, task.title), tint = text)
        }
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge, color = text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val note = dayNote(context, task, today)
            if (note.isNotEmpty()) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = if (overdue) MaterialTheme.colorScheme.error else secondary)
            }
        }
    }
}

/** «Google Tasks · 9:30» или «просрочено с 29 окт.». */
private fun dayNote(context: Context, task: DayTask, today: LocalDate): String = listOfNotNull(
    if (task.source == TaskSource.GOOGLE) context.getString(R.string.tasks_google) else null,
    if (TaskRules.overdue(task, today)) context.getString(R.string.tasks_overdue_since, TimeText.date(context, task.date)) else null,
    task.time?.let { TimeText.time(context, it.hour, it.minute) },
).joinToString(" · ")

/** Срок словами: «Сегодня, 11:00», «Завтра», «3 нояб.», «Без срока». */
internal fun dueText(context: Context, date: LocalDate?, time: LocalTime?, today: LocalDate): String {
    if (date == null) return context.getString(R.string.tasks_no_date)
    val day = when (date) {
        today -> context.getString(R.string.tasks_today_short)
        today.plusDays(1) -> context.getString(R.string.tasks_tomorrow)
        else -> TimeText.date(context, date)
    }
    return listOfNotNull(day, time?.let { TimeText.time(context, it.hour, it.minute) }).joinToString(", ")
}

internal enum class RepeatKind { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

internal fun TaskRepeat.kind(): RepeatKind = when (this) {
    TaskRepeat.None -> RepeatKind.NONE
    TaskRepeat.Daily -> RepeatKind.DAILY
    is TaskRepeat.Weekly -> RepeatKind.WEEKLY
    is TaskRepeat.Monthly -> RepeatKind.MONTHLY
    is TaskRepeat.Yearly -> RepeatKind.YEARLY
}

internal fun repeatKindName(kind: RepeatKind): Int = when (kind) {
    RepeatKind.NONE -> R.string.tasks_repeat_none
    RepeatKind.DAILY -> R.string.tasks_repeat_daily
    RepeatKind.WEEKLY -> R.string.tasks_repeat_weekly
    RepeatKind.MONTHLY -> R.string.tasks_repeat_monthly
    RepeatKind.YEARLY -> R.string.tasks_repeat_yearly
}

internal fun reminderName(context: Context, minutes: Int?): String = when (minutes) {
    null -> context.getString(R.string.tasks_reminder_none)
    0 -> context.getString(R.string.tasks_reminder_due)
    else -> context.getString(R.string.warning_before, leadText(context, minutes))
}

/** «Низкая», «Средняя»… — сложность и срочность (D-033). */
fun levelName(level: TaskLevel): Int = when (level) {
    TaskLevel.NONE -> R.string.tasks_level_none
    TaskLevel.LOW -> R.string.tasks_level_low
    TaskLevel.MEDIUM -> R.string.tasks_level_medium
    TaskLevel.HIGH -> R.string.tasks_level_high
}

/** «Через 3 д», «через 1 мес» — тихое напоминание постоянной (D-033). */
private fun afterName(context: Context, days: Int?): String =
    if (days == null) context.getString(R.string.tasks_reminder_none) else context.getString(R.string.tasks_after, elapsedText(context, days * 86_400_000L))

/**
 * Редактор своей задачи (D-032; выбор автора — срок, напоминание, повтор, заметка,
 * флажок «В календарь»). [task] null — новая: со сроком [today] или постоянная
 * ([permanent], D-033), подзадача [parentId]. У постоянной вместо срока — тихие
 * напоминания; у существующей — подзадачи и история отметок.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditor(task: Task?, today: LocalDate, onDismiss: () -> Unit, parentId: String? = null, permanent: Boolean = false) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val repository = DpClockApp.graph(context).repository
    val calendarSettings by DpClockApp.graph(context).calendar.settings.collectAsStateWithLifecycle()
    val all by repository.tasks.collectAsStateWithLifecycle(emptyList())
    var title by rememberSaveable { mutableStateOf(task?.title.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(task?.note.orEmpty()) }
    var isPermanent by rememberSaveable { mutableStateOf(task?.permanent ?: permanent) }
    var date by rememberSaveable { mutableStateOf(if (task == null) (if (permanent) null else today) else task.date) }
    var time by rememberSaveable { mutableStateOf(task?.time) }
    var reminder by rememberSaveable { mutableStateOf(task?.reminder) }
    var repeatCode by rememberSaveable { mutableStateOf(task?.repeat?.code.orEmpty()) }
    var inCalendar by rememberSaveable { mutableStateOf(task?.inCalendar ?: false) }
    var difficulty by rememberSaveable { mutableStateOf(task?.difficulty ?: TaskLevel.NONE) }
    var urgency by rememberSaveable { mutableStateOf(task?.urgency ?: TaskLevel.NONE) }
    var remindLate by rememberSaveable { mutableStateOf(task?.remindLate ?: false) }
    var remindAfterDays by rememberSaveable { mutableStateOf(task?.remindAfterDays) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var addingSubtask by rememberSaveable { mutableStateOf(false) }
    var openSubtask by remember { mutableStateOf<Task?>(null) }
    val calendarSetup = rememberTaskCalendarSetup()
    val repeat = TaskRepeat.decode(repeatCode)
    val parent = (task?.parentId ?: parentId)?.let { id -> all.firstOrNull { it.id == id } }
    val subtasks = task?.let { current -> all.filter { it.parentId == current.id } }.orEmpty()

    fun save() {
        val base = task ?: Task(UUID.randomUUID().toString(), "", createdAt = System.currentTimeMillis(), parentId = parentId)
        val nudgeChanged = base.remindLate != remindLate || base.remindAfterDays != remindAfterDays || base.permanent != isPermanent
        val saved = base.copy(
            title = title,
            note = note,
            permanent = isPermanent,
            date = if (isPermanent) null else date,
            time = if (isPermanent) null else time,
            reminder = if (isPermanent) null else reminder,
            repeat = if (isPermanent) TaskRepeat.None else repeat,
            inCalendar = !isPermanent && inCalendar,
            difficulty = difficulty,
            urgency = urgency,
            remindLate = isPermanent && remindLate,
            remindAfterDays = if (isPermanent) remindAfterDays else null,
            // Настройки напоминания поменялись — напомнить можно снова.
            nudged = if (nudgeChanged) null else base.nudged,
            done = if (isPermanent) false else base.done,
        )
        onDismiss()
        persistLater { repository.saveTask(saved) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                TopAppBar(
                    title = { TitleText(stringResource(if (task == null) R.string.tasks_new else R.string.tasks_edit)) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.cancel)) } },
                    actions = { TextButton(enabled = title.isNotBlank(), onClick = ::save) { Text(stringResource(R.string.save)) } },
                )
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (parent != null) {
                        Text(stringResource(R.string.tasks_subtask_of, parent.title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(Task.MAX_TITLE) },
                        label = { Text(stringResource(R.string.tasks_title)) },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(Task.MAX_NOTE) },
                        label = { Text(stringResource(R.string.tasks_note)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchRow(stringResource(R.string.tasks_permanent), isPermanent, stringResource(R.string.tasks_permanent_text)) { isPermanent = it }
                    if (isPermanent) {
                        SwitchRow(stringResource(R.string.tasks_remind_late), remindLate, stringResource(R.string.tasks_remind_late_text)) { remindLate = it }
                        ChoiceStepper(
                            stringResource(R.string.tasks_remind_after),
                            listOf<Int?>(null) + TaskHistory.AFTER_DAYS,
                            remindAfterDays,
                            text = { afterName(context, it) },
                        ) { remindAfterDays = it }
                    } else {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.tasks_due)) },
                            supportingContent = { Text(dueText(context, date, null, today)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { picking = "date" },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { date = today }, label = { Text(stringResource(R.string.tasks_today_short)) })
                            AssistChip(onClick = { date = today.plusDays(1) }, label = { Text(stringResource(R.string.tasks_tomorrow)) })
                            AssistChip(onClick = { date = null }, label = { Text(stringResource(R.string.tasks_no_date)) })
                        }
                        val day = date
                        if (day != null) {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.tasks_time)) },
                                supportingContent = { Text(time?.let { TimeText.time(context, it.hour, it.minute) } ?: stringResource(R.string.tasks_no_time)) },
                                trailingContent = {
                                    if (time != null) {
                                        IconButton(onClick = { time = null }) { Icon(AppIcons.Remove, contentDescription = stringResource(R.string.tasks_no_time)) }
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable { picking = "time" },
                            )
                            ChoiceStepper(
                                stringResource(R.string.tasks_reminder),
                                listOf<Int?>(null) + TaskRules.REMINDERS,
                                reminder,
                                text = { reminderName(context, it) },
                            ) { reminder = it }
                            ChoiceStepper(
                                stringResource(R.string.tasks_repeat),
                                RepeatKind.entries.toList(),
                                repeat.kind(),
                                text = { resources.getString(repeatKindName(it)) },
                            ) { kind ->
                                repeatCode = when (kind) {
                                    RepeatKind.NONE -> TaskRepeat.None
                                    RepeatKind.DAILY -> TaskRepeat.Daily
                                    RepeatKind.WEEKLY -> TaskRepeat.Weekly(setOf(day.dayOfWeek))
                                    RepeatKind.MONTHLY -> TaskRepeat.Monthly(day.dayOfMonth)
                                    RepeatKind.YEARLY -> TaskRepeat.Yearly(day.monthValue, day.dayOfMonth)
                                }.code
                            }
                            if (repeat is TaskRepeat.Weekly) {
                                DayChips(repeat.days) { toggled ->
                                    val days = if (toggled in repeat.days) repeat.days - toggled else repeat.days + toggled
                                    repeatCode = TaskRepeat.Weekly(days.ifEmpty { setOf<DayOfWeek>(toggled) }).code
                                }
                            }
                        }
                        SwitchRow(
                            stringResource(R.string.tasks_in_calendar),
                            inCalendar,
                            supporting = when {
                                !inCalendar -> stringResource(R.string.tasks_in_calendar_text)
                                date == null -> stringResource(R.string.tasks_in_calendar_no_date)
                                calendarSettings.taskCalendar == null -> stringResource(R.string.tasks_calendar_none)
                                else -> stringResource(R.string.tasks_in_calendar_on)
                            },
                        ) { on ->
                            if (on) calendarSetup { inCalendar = true } else inCalendar = false
                        }
                    }
                    ChoiceStepper(stringResource(R.string.tasks_difficulty), TaskLevel.entries.toList(), difficulty, text = { resources.getString(levelName(it)) }) { difficulty = it }
                    ChoiceStepper(stringResource(R.string.tasks_urgency), TaskLevel.entries.toList(), urgency, text = { resources.getString(levelName(it)) }) { urgency = it }
                    if (task != null) {
                        HorizontalDivider()
                        Text(stringResource(R.string.tasks_subtasks), style = MaterialTheme.typography.titleSmall)
                        for (sub in subtasks) {
                            Text(
                                (if (sub.done) "✓ " else "") + sub.title,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clickable { openSubtask = sub }.padding(vertical = 8.dp),
                            )
                        }
                        TextButton(onClick = { addingSubtask = true }) { Text(stringResource(R.string.tasks_add_subtask)) }
                        HorizontalDivider()
                        TaskHistoryBlock(task)
                        HorizontalDivider()
                        TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    when (picking) {
        "date" -> {
            val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        picking = null
                    }) { Text(stringResource(R.string.ok)) }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.cancel)) } },
            ) { DatePicker(state) }
        }
        "time" -> {
            val initial = time ?: LocalTime.of(TaskRules.DAY_HOUR, 0)
            TimePickerDialog(initial.hour, initial.minute, onDismiss = { picking = null }) { hour, minute ->
                time = LocalTime.of(hour, minute)
                picking = null
            }
        }
    }
    if (addingSubtask && task != null) TaskEditor(null, today, onDismiss = { addingSubtask = false }, parentId = task.id, permanent = task.permanent)
    openSubtask?.let { sub -> TaskEditor(sub, today, onDismiss = { openSubtask = null }) }
    if (confirmDelete && task != null) {
        val nested = TaskTree.descendants(all, task.id).size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.tasks_delete_title)) },
            text = {
                Text(if (nested == 0) task.title else task.title + "\n" + pluralStringResource(R.plurals.tasks_delete_subtasks, nested, nested))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDismiss()
                    persistLater { repository.deleteTask(task.id) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * История отметок задачи (D-033; идея — DP_Repeated_Tasks): сколько раз, «обычно раз
 * в … (от … до …)», сколько прошло; отметки с промежутком до предыдущей; «Добавить
 * прошлую отметку» и удаление ошибочной.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskHistoryBlock(task: Task) {
    val context = LocalContext.current
    val repository = DpClockApp.graph(context).repository
    val all by repository.marks.collectAsStateWithLifecycle(emptyList())
    val marks = all.filter { it.taskId == task.id }.sortedBy { it.at }
    val stats = TaskHistory.stats(marks.map { it.at })
    val now = rememberNowMillis(60_000L)
    var showAll by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    var addDate by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var deleting by remember { mutableStateOf<TaskMark?>(null) }
    Text(stringResource(R.string.tasks_history), style = MaterialTheme.typography.titleSmall)
    val last = stats.last
    Text(
        listOfNotNull(
            pluralStringResource(R.plurals.tasks_done_times, stats.count, stats.count),
            stats.average?.let { avg ->
                stringResource(R.string.tasks_usually_range, elapsedText(context, avg), elapsedText(context, stats.shortest ?: avg), elapsedText(context, stats.longest ?: avg))
            },
            if (last == null) stringResource(R.string.tasks_never_done) else stringResource(R.string.tasks_ago, elapsedText(context, now - last)),
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = if (TaskHistory.late(stats, now)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val newest = marks.reversed()
    val shown = if (showAll) newest else newest.take(HISTORY_ROWS)
    for (mark in shown) {
        val index = marks.indexOf(mark)
        val gap = if (index > 0) mark.at - marks[index - 1].at else null
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(DateUtils.formatDateTime(context, mark.at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH))
                if (gap != null) Text(stringResource(R.string.tasks_after_previous, elapsedText(context, gap)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { deleting = mark }) { Icon(AppIcons.Delete, contentDescription = stringResource(R.string.tasks_delete_mark)) }
        }
    }
    if (newest.size > shown.size) TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.tasks_more, newest.size - shown.size)) }
    TextButton(onClick = { adding = "date" }) { Text(stringResource(R.string.tasks_add_mark)) }

    when (adding) {
        "date" -> {
            val state = rememberDatePickerState(initialSelectedDateMillis = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { adding = null },
                confirmButton = {
                    TextButton(onClick = {
                        addDate = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        adding = if (addDate != null) "time" else null
                    }) { Text(stringResource(R.string.ok)) }
                },
                dismissButton = { TextButton(onClick = { adding = null }) { Text(stringResource(R.string.cancel)) } },
            ) { DatePicker(state) }
        }
        "time" -> TimePickerDialog(12, 0, onDismiss = { adding = null }) { hour, minute ->
            val day = addDate
            adding = null
            if (day != null) {
                val at = day.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                persistLater { repository.addMark(task.id, at) }
            }
        }
    }
    deleting?.let { mark ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.tasks_delete_mark)) },
            text = { Text(DateUtils.formatDateTime(context, mark.at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    persistLater { repository.deleteMark(mark.id) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Сколько отметок видно сразу. */
private const val HISTORY_ROWS = 10

/**
 * Готовность к записи в календарь (D-032, п. 6): разрешение на чтение и запись
 * календаря, потом — выбор календаря, если он ещё не выбран. Всё есть — сразу [then].
 */
@Composable
fun rememberTaskCalendarSetup(): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    val store = DpClockApp.graph(context).calendar
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (TaskCalendar.granted(context)) {
            if (store.settings.value.taskCalendar == null) choosing = true else pending?.invoke().also { pending = null }
        } else {
            pending = null
        }
    }
    if (choosing) {
        TaskCalendarDialog(
            onDismiss = {
                choosing = false
                pending = null
            },
        ) { id ->
            store.update { it.copy(taskCalendar = id) }
            choosing = false
            pending?.invoke()
            pending = null
        }
    }
    return { then ->
        pending = then
        when {
            !TaskCalendar.granted(context) -> permissions.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            store.settings.value.taskCalendar == null -> choosing = true
            else -> {
                pending = null
                then()
            }
        }
    }
}

/** Выбор календаря для задач: только те, куда можно писать. */
@Composable
private fun TaskCalendarDialog(onDismiss: () -> Unit, onChoose: (Long) -> Unit) {
    val context = LocalContext.current
    val calendars by produceState<List<PhoneCalendarInfo>?>(null) { value = withContext(Dispatchers.IO) { TaskCalendar.writable(context) } }
    val settings by DpClockApp.graph(context).calendar.settings.collectAsStateWithLifecycle()
    val current = settings.taskCalendar
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tasks_calendar)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.tasks_calendar_text), style = MaterialTheme.typography.bodyMedium)
                val list = calendars
                when {
                    list == null -> Text(stringResource(R.string.tasks_loading))
                    list.isEmpty() -> Text(stringResource(R.string.tasks_calendar_empty))
                    else -> for (calendar in list) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().selectable(selected = calendar.id == current, role = Role.RadioButton) { onChoose(calendar.id) },
                        ) {
                            RadioButton(selected = calendar.id == current, onClick = null)
                            Column(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)) {
                                Text(calendar.name.ifBlank { calendar.account })
                                if (calendar.account.isNotBlank()) Text(calendar.account, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Раздел настроек «Задачи» (D-032): календарь для задач, Google Tasks входом в
 * браузере, где показывать задачи дня, «Обновить сейчас».
 */
@Composable
fun TaskSettings(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val graph = DpClockApp.graph(context)
    val store = graph.tasks
    val state by store.state.collectAsStateWithLifecycle()
    val calendarSettings by graph.calendar.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var googleDialog by rememberSaveable { mutableStateOf(false) }
    var disconnect by rememberSaveable { mutableStateOf(false) }
    var choosingCalendar by rememberSaveable { mutableStateOf(false) }
    val calendarSetup = rememberTaskCalendarSetup()
    val calendars = rememberPhoneCalendars(TaskCalendar.granted(context))

    fun show(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    Text(
        stringResource(R.string.tasks_text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    val cache = state.cache
    ListItem(
        headlineContent = { Text(stringResource(R.string.tasks_calendar)) },
        supportingContent = {
            Text(calendarSettings.taskCalendar?.let { id -> calendars.firstOrNull { it.id == id }?.let { it.name.ifBlank { it.account } } } ?: stringResource(R.string.tasks_calendar_none))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { if (calendarSettings.taskCalendar == null) calendarSetup {} else choosingCalendar = true },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.tasks_google)) },
        supportingContent = {
            Text(
                when {
                    !cache.google -> stringResource(R.string.tasks_off)
                    cache.failure == TaskCache.SIGN_IN || cache.failure == 401 || cache.failure == 403 -> stringResource(R.string.tasks_sign_in_again)
                    cache.failure != null -> stringResource(R.string.tasks_not_updated)
                    else -> stringResource(R.string.tasks_on)
                },
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { if (cache.google && cache.failure == null) disconnect = true else googleDialog = true },
    )
    SwitchRow(stringResource(R.string.tasks_show_clock), cache.showClock) { on -> persistLater { store.updateShow { it.copy(showClock = on) } } }
    SwitchRow(stringResource(R.string.tasks_show_after_alarm), cache.showAfterAlarm, stringResource(R.string.tasks_show_after_alarm_text)) { on ->
        persistLater { store.updateShow { it.copy(showAfterAlarm = on) } }
    }
    SwitchRow(stringResource(R.string.tasks_show_widget), cache.showWidget) { on -> persistLater { store.updateShow { it.copy(showWidget = on) } } }
    if (cache.google) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.tasks_refresh)) },
            supportingContent = {
                Text(
                    when {
                        state.refreshing -> stringResource(R.string.tasks_loading)
                        cache.fetchedAt > 0 -> stringResource(
                            R.string.tasks_refreshed,
                            DateUtils.formatDateTime(context, cache.fetchedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH),
                        )
                        else -> stringResource(R.string.tasks_never)
                    },
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { scope.launch { persist { store.refresh(force = true) } } },
        )
    }

    if (googleDialog) {
        GoogleDialog(onDismiss = { googleDialog = false }) { result ->
            googleDialog = false
            show(
                when (result) {
                    GoogleSignIn.Done -> resources.getString(R.string.tasks_connected)
                    is GoogleSignIn.Failed -> resources.getString(R.string.tasks_google_failed, result.reason)
                },
            )
        }
    }
    if (choosingCalendar) {
        TaskCalendarDialog(onDismiss = { choosingCalendar = false }) { id ->
            graph.calendar.update { it.copy(taskCalendar = id) }
            choosingCalendar = false
        }
    }
    if (disconnect) {
        AlertDialog(
            onDismissRequest = { disconnect = false },
            title = { Text(stringResource(R.string.tasks_google)) },
            text = { Text(stringResource(R.string.tasks_disconnect_text)) },
            confirmButton = {
                TextButton(onClick = {
                    disconnect = false
                    scope.launch {
                        persist { store.disconnect() }
                        show(resources.getString(R.string.tasks_disconnected))
                    }
                }) { Text(stringResource(R.string.tasks_disconnect)) }
            },
            dismissButton = { TextButton(onClick = { disconnect = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Вход в Google Tasks в браузере (D-032; выбор автора — веб-форма): Client ID и
 * секрет клиента «Компьютерное приложение» из своего проекта Google Cloud, потом —
 * страница Google в браузере. Пока ждём браузер — «Отмена».
 */
@Composable
private fun GoogleDialog(onDismiss: () -> Unit, onResult: (GoogleSignIn) -> Unit) {
    val context = LocalContext.current
    val store = DpClockApp.graph(context).tasks
    val scope = rememberCoroutineScope()
    var clientId by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    var waiting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {
            store.cancelSignIn()
            onDismiss()
        },
        title = { Text(stringResource(R.string.tasks_google)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (waiting) {
                    Text(stringResource(R.string.tasks_google_waiting))
                } else {
                    Text(stringResource(R.string.tasks_google_setup_text), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it.trim() },
                        label = { Text(stringResource(R.string.tasks_google_client_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it.trim() },
                        label = { Text(stringResource(R.string.tasks_google_secret)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (!waiting) {
                TextButton(enabled = clientId.isNotBlank() && secret.isNotBlank(), onClick = {
                    waiting = true
                    scope.launch {
                        val result = persist {
                            store.signIn(clientId, secret) { url ->
                                withContext(Dispatchers.Main) {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                        }
                        waiting = false
                        onResult(result)
                    }
                }) { Text(stringResource(R.string.tasks_google_sign_in)) }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                store.cancelSignIn()
                onDismiss()
            }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** «3 задачи · Купить хлеб» (D-032) для виджета — без сети. */
fun widgetTasksText(context: Context, own: List<Task>, cache: TaskCache, today: LocalDate): String? {
    if (!cache.showWidget) return null
    val tasks = dayTasks(own, cache, today)
    if (tasks.isEmpty()) return if (own.isEmpty() && !cache.google) null else context.getString(R.string.tasks_none)
    return context.resources.getQuantityString(R.plurals.tasks_count, tasks.size, tasks.size) + " · " + tasks.first().title
}
