package com.dpsmpx.dpclock.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.QuickRequest
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.Section
import com.dpsmpx.dpclock.SectionRequest
import com.dpsmpx.dpclock.model.Task
import com.dpsmpx.dpclock.model.TaskHistory
import com.dpsmpx.dpclock.model.TaskLevel
import com.dpsmpx.dpclock.model.TaskRepeat
import com.dpsmpx.dpclock.model.TaskRules
import com.dpsmpx.dpclock.model.TaskSort
import com.dpsmpx.dpclock.model.TaskStats
import com.dpsmpx.dpclock.model.TaskTree
import com.dpsmpx.dpclock.persist
import com.dpsmpx.dpclock.persistLater
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Раздел «Таймеры» (D-033; выбор автора — секундомер переезжает к таймерам):
 * вкладки «Таймеры» и «Секундомер». Ярлык и уведомление секундомера открывают его вкладку.
 */
@Composable
fun TimersSection(requestNotifications: () -> Unit, quick: QuickRequest?, onQuickHandled: (Long) -> Unit, request: SectionRequest) {
    var tab by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(request) {
        if (request.serial >= 0 && request.section == Section.TIMERS) tab = if (request.stopwatch) 1 else 0
    }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.section_timers)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.section_stopwatch)) })
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) TimersScreen(requestNotifications, quick = quick, onQuickHandled = onQuickHandled) else StopwatchScreen()
        }
    }
}

private enum class TasksTab(val title: Int) { ALL(R.string.tasks_tab_all), PERMANENT(R.string.tasks_tab_permanent), BOARD(R.string.tasks_tab_board) }

/**
 * Раздел «Задачи» (D-033; выбор автора — свой раздел на месте секундомера, вкладки
 * вверху): «Все», «Постоянные», «Доска»; поиск и сортировка; «+» — новая задача.
 */
@Composable
fun TasksScreen(snackbar: SnackbarHostState, initialTab: Int = 0) {
    val context = LocalContext.current
    val graph = DpClockApp.graph(context)
    // null — ещё не прочитано: список не рисуется, чтобы не мелькало «задач пока нет».
    val loaded by graph.repository.tasks.collectAsStateWithLifecycle(null)
    val loadedMarks by graph.repository.marks.collectAsStateWithLifecycle(null)
    val own = loaded.orEmpty()
    val marks = loadedMarks.orEmpty()
    // Доска, сложность и срочность — за настройкой (D-036, п. 3).
    val advanced = rememberTasksAdvanced()
    val tabs = if (advanced) TasksTab.entries else TasksTab.entries - TasksTab.BOARD
    var chosenTab by rememberSaveable { mutableStateOf(TasksTab.entries[initialTab]) }
    val tab = chosenTab.takeIf { it in tabs } ?: TasksTab.ALL
    var query by rememberSaveable { mutableStateOf("") }
    var allSort by rememberSaveable { mutableStateOf(TaskSort.DUE) }
    var permanentSort by rememberSaveable { mutableStateOf(TaskSort.ELAPSED) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Task?>(null) }
    val today = rememberToday()
    val now = rememberNowMillis(60_000L)
    val byTask = marks.groupBy { it.taskId }.mapValues { (_, list) -> TaskHistory.stats(list.map { it.at }) }
    val last = byTask.mapNotNull { (id, stats) -> stats.last?.let { id to it } }.toMap()
    val found = own.filter { TaskTree.matches(it, query) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab)) {
                for (item in tabs) Tab(selected = tab == item, onClick = { chosenTab = item }, text = { Text(stringResource(item.title)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.tasks_search)) },
                    leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (tab != TasksTab.BOARD) {
                    SortMenu(if (tab == TasksTab.ALL) allSort else permanentSort, advanced) { sort -> if (tab == TasksTab.ALL) allSort = sort else permanentSort = sort }
                }
            }
            if (loaded == null || loadedMarks == null) return@Column
            when (tab) {
                TasksTab.ALL -> AllTab(found, query, allSort, last, byTask, now, today, snackbar) { editing = it }
                TasksTab.PERMANENT -> PermanentTab(found.filter { it.permanent }, permanentSort, last, byTask, now, today, snackbar) { editing = it }
                TasksTab.BOARD -> BoardTab(found.filter { !it.done }, last, now, today) { editing = it }
            }
        }
        FloatingActionButton(onClick = { creating = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(AppIcons.Add, contentDescription = stringResource(R.string.tasks_new))
        }
    }
    if (creating) TaskEditor(null, today, onDismiss = { creating = false }, permanent = tab == TasksTab.PERMANENT)
    editing?.let { task -> TaskEditor(task, today, onDismiss = { editing = null }) }
}

@Composable
private fun SortMenu(sort: TaskSort, advanced: Boolean, onChange: (TaskSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(AppIcons.Sort, contentDescription = stringResource(R.string.tasks_sort)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Выбранный раньше порядок остаётся в списке и без настройки.
            for (item in TaskSort.entries.filter { advanced || it == sort || (it != TaskSort.URGENCY && it != TaskSort.DIFFICULTY) }) {
                DropdownMenuItem(
                    text = { Text(stringResource(sortName(item))) },
                    leadingIcon = { RadioButton(selected = item == sort, onClick = null) },
                    onClick = {
                        open = false
                        onChange(item)
                    },
                )
            }
        }
    }
}

private fun sortName(sort: TaskSort): Int = when (sort) {
    TaskSort.DUE -> R.string.tasks_sort_due
    TaskSort.ELAPSED -> R.string.tasks_sort_elapsed
    TaskSort.NAME -> R.string.tasks_sort_name
    TaskSort.URGENCY -> R.string.tasks_sort_urgency
    TaskSort.DIFFICULTY -> R.string.tasks_sort_difficulty
}

/** Отметить «сделано»; у постоянной — «Отмечено · Отменить»: отметка снимается. */
private fun markDone(context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, snackbar: SnackbarHostState, task: Task) {
    val repository = DpClockApp.graph(context).repository
    val resources = context.resources
    scope.launch {
        val mark = persist { repository.completeTask(task.id) } ?: return@launch
        if (!task.permanent) return@launch
        snackbar.currentSnackbarData?.dismiss()
        val result = snackbar.showSnackbar(resources.getString(R.string.tasks_marked, task.title), resources.getString(R.string.undo), duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) persist { repository.deleteMark(mark.id) }
    }
}

/**
 * «Все»: свои задачи группами по сроку — просрочено, сегодня, завтра, позже, без срока,
 * постоянные; подзадачи — под своей задачей, ветки сворачиваются. С поиском или другой
 * сортировкой — одним списком. Ниже — сделанные.
 */
@Composable
private fun AllTab(
    tasks: List<Task>,
    query: String,
    sort: TaskSort,
    last: Map<String, Long>,
    stats: Map<String, TaskStats>,
    now: Long,
    today: LocalDate,
    snackbar: SnackbarHostState,
    onOpen: (Task) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val open = tasks.filter { !it.done }
    val done = tasks.filter { it.done }.sortedByDescending { it.doneAt ?: 0L }
    val order = TaskTree.order(sort, last)
    fun toggle(id: String) {
        collapsed = if (id in collapsed) collapsed - id else collapsed + id
    }
    val sections: List<Pair<Int?, List<Pair<Task, Int>>>> = if (sort == TaskSort.DUE && query.isBlank()) {
        val roots = open.filter { task -> task.parentId == null || open.none { it.id == task.parentId } }
        fun group(predicate: (Task) -> Boolean): List<Pair<Task, Int>> {
            val chosen = roots.filter(predicate).map { it.id }.toSet()
            val keep = chosen + chosen.flatMap { TaskTree.descendants(open, it) }
            return TaskTree.flatten(open.filter { it.id in keep }, collapsed.toSet(), order)
        }
        listOf(
            R.string.tasks_group_overdue to group { !it.permanent && it.date?.let { d -> d < today } == true },
            R.string.tasks_today_short to group { !it.permanent && it.date == today },
            R.string.tasks_tomorrow to group { !it.permanent && it.date == today.plusDays(1) },
            R.string.tasks_group_later to group { !it.permanent && it.date?.let { d -> d > today.plusDays(1) } == true },
            R.string.tasks_no_date to group { !it.permanent && it.date == null },
            R.string.tasks_tab_permanent to group { it.permanent },
        )
    } else {
        listOf(null to TaskTree.flatten(open, collapsed.toSet(), order))
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp)) {
        if (tasks.isEmpty()) {
            item { Text(stringResource(if (query.isBlank()) R.string.tasks_empty else R.string.tasks_not_found), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        for ((title, rows) in sections) {
            if (rows.isEmpty()) continue
            if (title != null) item(key = "h$title") { GroupTitle(stringResource(title)) }
            items(rows, key = { it.first.id }) { (task, depth) ->
                TaskRow(task, depth, open, collapsed.contains(task.id), stats[task.id], now, today, onToggle = { toggle(task.id) }, onDone = { markDone(context, scope, snackbar, task) }, onOpen = { onOpen(task) })
            }
        }
        if (done.isNotEmpty()) {
            item(key = "done") {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(onClick = { showDone = !showDone }) { Text(stringResource(if (showDone) R.string.tasks_hide_done else R.string.tasks_show_done, done.size)) }
            }
            if (showDone) {
                items(done, key = { "d" + it.id }) { task ->
                    TaskRow(task, 0, tasks, collapsed = false, stats[task.id], now, today, onToggle = {}, onDone = {}, onOpen = { onOpen(task) })
                }
            }
        }
    }
}

/**
 * «Постоянные» (выбор автора): задачи, которые не закрываются; у каждой — сколько
 * прошло с прошлой отметки, дольше обычного — выделено. По умолчанию — давно не
 * сделанные сверху.
 */
@Composable
private fun PermanentTab(
    tasks: List<Task>,
    sort: TaskSort,
    last: Map<String, Long>,
    stats: Map<String, TaskStats>,
    now: Long,
    today: LocalDate,
    snackbar: SnackbarHostState,
    onOpen: (Task) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val rows = TaskTree.flatten(tasks, collapsed.toSet(), TaskTree.order(sort, last))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp)) {
        if (rows.isEmpty()) {
            item { Text(stringResource(R.string.tasks_permanent_empty), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(rows, key = { it.first.id }) { (task, depth) ->
            TaskRow(
                task,
                depth,
                tasks,
                task.id in collapsed,
                stats[task.id],
                now,
                today,
                onToggle = { collapsed = if (task.id in collapsed) collapsed - task.id else collapsed + task.id },
                onDone = { markDone(context, scope, snackbar, task) },
                onOpen = { onOpen(task) },
            )
        }
    }
}

/**
 * «Доска» (выбор автора — сложность и срочность): колонки «Высокая», «Средняя»,
 * «Низкая» по выбранному признаку; без оценки — ниже.
 */
@Composable
private fun BoardTab(tasks: List<Task>, last: Map<String, Long>, now: Long, today: LocalDate, onOpen: (Task) -> Unit) {
    val context = LocalContext.current
    var byUrgency by rememberSaveable { mutableStateOf(true) }
    val level = { task: Task -> if (byUrgency) task.urgency else task.difficulty }
    val order = TaskTree.order(TaskSort.ELAPSED, last)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 96.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
            FilterChip(selected = byUrgency, onClick = { byUrgency = true }, label = { Text(stringResource(R.string.tasks_urgency)) })
            FilterChip(selected = !byUrgency, onClick = { byUrgency = false }, label = { Text(stringResource(R.string.tasks_difficulty)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            for (column in listOf(TaskLevel.HIGH, TaskLevel.MEDIUM, TaskLevel.LOW)) {
                Column(
                    Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(levelName(column)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(4.dp))
                    for (task in tasks.filter { level(it) == column }.sortedWith(order)) BoardCard(task, last[task.id], now, today) { onOpen(task) }
                }
            }
        }
        val unrated = tasks.filter { level(it) == TaskLevel.NONE }.sortedWith(order)
        if (unrated.isNotEmpty()) {
            GroupTitle(stringResource(R.string.tasks_level_none))
            for (task in unrated) {
                Text(
                    task.title + (boardNote(context, task, last[task.id], now, today)?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(task) }.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun BoardCard(task: Task, last: Long?, now: Long, today: LocalDate, onOpen: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(8.dp),
    ) {
        Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        boardNote(context, task, last, now, today)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun boardNote(context: android.content.Context, task: Task, last: Long?, now: Long, today: LocalDate): String? = when {
    task.permanent -> if (last == null) context.getString(R.string.tasks_never_done) else context.getString(R.string.tasks_ago, elapsedText(context, now - last))
    task.date != null -> dueText(context, task.date, task.time, today)
    else -> null
}

@Composable
internal fun GroupTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

/**
 * Строка своей задачи: отступ подзадачи и стрелка ветки, кружок «сделано», название,
 * срок, повтор, напоминание, сложность и срочность; у постоянной и повторяющейся —
 * сколько прошло с прошлой отметки (выбор автора), дольше обычного — красным.
 */
@Composable
private fun TaskRow(
    task: Task,
    depth: Int,
    all: List<Task>,
    collapsed: Boolean,
    stats: TaskStats?,
    now: Long,
    today: LocalDate,
    onToggle: () -> Unit,
    onDone: () -> Unit,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val repository = DpClockApp.graph(context).repository
    val advanced = rememberTasksAdvanced()
    val overdue = !task.done && !task.permanent && task.date?.let { it < today } == true
    val late = stats != null && TaskHistory.late(stats, now)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = (depth * 20).dp)) {
        if (TaskTree.hasChildren(all, task.id)) {
            IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                Icon(if (collapsed) AppIcons.ExpandRight else AppIcons.ExpandMore, contentDescription = stringResource(if (collapsed) R.string.tasks_expand else R.string.tasks_collapse))
            }
        } else {
            Spacer(Modifier.width(32.dp))
        }
        IconButton(onClick = { if (task.done) persistLater { repository.reopenTask(task.id) } else onDone() }) {
            Icon(
                if (task.done) AppIcons.TaskDone else AppIcons.TaskOpen,
                contentDescription = stringResource(if (task.done) R.string.tasks_mark_open else R.string.tasks_mark_done, task.title),
            )
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val lastAt = stats?.last
            if (task.permanent || task.repeat != TaskRepeat.None) {
                val ago = if (lastAt == null) {
                    stringResource(R.string.tasks_never_done)
                } else {
                    listOfNotNull(
                        stringResource(R.string.tasks_ago, elapsedText(context, now - lastAt)),
                        stats.average?.let { stringResource(R.string.tasks_usually, elapsedText(context, it)) },
                    ).joinToString(" · ")
                }
                Text(ago, style = MaterialTheme.typography.bodySmall, color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
            }
            val details = listOfNotNull(
                task.date?.let { dueText(context, it, task.time, today) },
                task.repeat.takeIf { it != TaskRepeat.None }?.let { resources.getString(repeatKindName(it.kind())) },
                task.reminder?.let { reminderName(context, it) },
                task.urgency.takeIf { advanced && it != TaskLevel.NONE }?.let { resources.getString(R.string.tasks_urgency_short, resources.getString(levelName(it)).lowercase()) },
                task.difficulty.takeIf { advanced && it != TaskLevel.NONE }?.let { resources.getString(R.string.tasks_difficulty_short, resources.getString(levelName(it)).lowercase()) },
                if (task.inCalendar) resources.getString(R.string.tasks_in_calendar) else null,
            ).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.note.isNotBlank()) Text(task.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
