package com.dpsmpx.dpclock.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.DayTask
import com.dpsmpx.dpclock.model.TaskCache
import com.dpsmpx.dpclock.model.TaskRules
import com.dpsmpx.dpclock.model.TaskService
import com.dpsmpx.dpclock.persist
import com.dpsmpx.dpclock.persistLater
import com.dpsmpx.dpclock.platform.GoogleAuth
import com.dpsmpx.dpclock.platform.GoogleTasksAuth
import com.dpsmpx.dpclock.platform.TaskConnect
import com.dpsmpx.dpclock.platform.TimeText
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Сколько задач видно в «Часах» сразу; остальные — по «Ещё». */
private const val CLOCK_ROWS = 3

/**
 * Задачи на сегодня в «Часах» (D-031; выбор автора — сегодня и просроченные,
 * с отметкой «сделано»). Пока раздел виден — обновляются не чаще раза в 15 минут.
 */
@Composable
fun TasksPanel(active: Boolean, onBackdrop: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = DpClockApp.graph(context).tasks
    val state by store.state.collectAsStateWithLifecycle()
    LaunchedEffect(active) { if (active) persistLater { store.refresh() } }
    val cache = state.cache
    if (cache.connected.isEmpty() || !cache.showClock) return
    val now = rememberNowMillis(60_000L)
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val tasks = state.today(today)
    var expanded by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val shown = if (expanded) tasks else tasks.take(CLOCK_ROWS)
    val text = if (onBackdrop) Color.White else MaterialTheme.colorScheme.onSurface
    val secondary = if (onBackdrop) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .then(if (onBackdrop) Modifier.background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(16.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(stringResource(R.string.tasks_today), style = MaterialTheme.typography.titleSmall, color = text)
        when {
            tasks.isEmpty() && state.refreshing -> Text(stringResource(R.string.tasks_loading), style = MaterialTheme.typography.bodyMedium, color = secondary)
            tasks.isEmpty() -> Text(stringResource(R.string.tasks_none), style = MaterialTheme.typography.bodyMedium, color = secondary)
        }
        for (task in shown) {
            TaskRow(task, today, text, secondary) {
                persistLater { failed = !store.complete(task) }
            }
        }
        if (tasks.size > shown.size) {
            TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.tasks_more, tasks.size - shown.size), color = text) }
        }
        if (failed) Text(stringResource(R.string.tasks_complete_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (cache.failures.isNotEmpty()) Text(stringResource(R.string.tasks_not_updated), style = MaterialTheme.typography.bodySmall, color = secondary)
    }
}

@Composable
private fun TaskRow(task: DayTask, today: LocalDate, text: Color, secondary: Color, onDone: () -> Unit) {
    val context = LocalContext.current
    val overdue = TaskRules.overdue(task, today)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onDone) {
            Icon(AppIcons.TaskOpen, contentDescription = stringResource(R.string.tasks_mark_done, task.title), tint = text)
        }
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge, color = text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                taskNote(context, task, today),
                style = MaterialTheme.typography.bodySmall,
                color = if (overdue) MaterialTheme.colorScheme.error else secondary,
            )
        }
    }
}

/** «Todoist · 9:30» или «TickTick · просрочено с 29 окт.». */
private fun taskNote(context: Context, task: DayTask, today: LocalDate): String = listOfNotNull(
    context.getString(serviceName(task.service)),
    if (TaskRules.overdue(task, today)) context.getString(R.string.tasks_overdue_since, TimeText.date(context, task.date)) else null,
    task.time?.let { TimeText.time(context, it.hour, it.minute) },
).joinToString(" · ")

fun serviceName(service: TaskService): Int = when (service) {
    TaskService.TODOIST -> R.string.tasks_todoist
    TaskService.TICKTICK -> R.string.tasks_ticktick
    TaskService.GOOGLE -> R.string.tasks_google
}

/**
 * Раздел настроек «Задачи» (D-031): подключение Todoist и TickTick личным ключом,
 * Google Tasks — входом Google; где показывать; «Обновить сейчас».
 */
@Composable
fun TaskSettings(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val store = DpClockApp.graph(context).tasks
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var keyFor by rememberSaveable { mutableStateOf<TaskService?>(null) }
    var disconnect by rememberSaveable { mutableStateOf<TaskService?>(null) }
    var googleSetup by rememberSaveable { mutableStateOf(false) }

    fun show(text: Int) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(resources.getString(text))
        }
    }

    fun connected(result: TaskConnect) {
        when (result) {
            TaskConnect.OK -> show(R.string.tasks_connected)
            TaskConnect.WRONG_KEY -> show(R.string.tasks_wrong_key)
            TaskConnect.NO_NETWORK -> show(R.string.tasks_no_network)
            TaskConnect.NOT_REGISTERED -> googleSetup = true
            TaskConnect.FAILED -> show(R.string.tasks_failed)
        }
    }

    fun google(auth: GoogleAuth, launch: (IntentSenderRequest) -> Unit) {
        when (auth) {
            is GoogleAuth.Granted -> scope.launch { connected(persist { store.connectGoogle() }) }
            is GoogleAuth.NeedsUi -> auth.result.pendingIntent?.let { launch(IntentSenderRequest.Builder(it).build()) } ?: show(R.string.tasks_failed)
            is GoogleAuth.Failed -> when (auth.code) {
                CommonStatusCodes.DEVELOPER_ERROR -> googleSetup = true
                CommonStatusCodes.CANCELED -> Unit
                CommonStatusCodes.NETWORK_ERROR -> show(R.string.tasks_no_network)
                else -> show(R.string.tasks_failed)
            }
        }
    }

    val googleWindow = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        google(GoogleTasksAuth.fromIntent(context, result.data)) { show(R.string.tasks_failed) }
    }

    Text(
        stringResource(R.string.tasks_text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    val cache = state.cache
    for (service in TaskService.entries) {
        val on = service in cache.connected
        val supporting = when {
            !on -> stringResource(R.string.tasks_off)
            cache.failures[service] == TaskCache.SIGN_IN || cache.failures[service] == 401 || cache.failures[service] == 403 -> stringResource(R.string.tasks_sign_in_again)
            cache.failures.containsKey(service) -> stringResource(R.string.tasks_not_updated)
            else -> stringResource(R.string.tasks_on)
        }
        ListItem(
            headlineContent = { Text(stringResource(serviceName(service))) },
            supportingContent = { Text(supporting) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable {
                when {
                    on && !cache.failures.containsKey(service) -> disconnect = service
                    service == TaskService.GOOGLE -> scope.launch { google(GoogleTasksAuth.silent(context)) { googleWindow.launch(it) } }
                    else -> keyFor = service
                }
            },
        )
    }
    if (cache.connected.isNotEmpty()) {
        SwitchRow(stringResource(R.string.tasks_show_clock), cache.showClock) { on -> persistLater { store.updateShow { it.copy(showClock = on) } } }
        SwitchRow(stringResource(R.string.tasks_show_after_alarm), cache.showAfterAlarm, stringResource(R.string.tasks_show_after_alarm_text)) { on ->
            persistLater { store.updateShow { it.copy(showAfterAlarm = on) } }
        }
        SwitchRow(stringResource(R.string.tasks_show_widget), cache.showWidget) { on -> persistLater { store.updateShow { it.copy(showWidget = on) } } }
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

    keyFor?.let { service ->
        KeyDialog(service, onDismiss = { keyFor = null }) { key ->
            keyFor = null
            scope.launch { connected(persist { store.connect(service, key) }) }
        }
    }
    disconnect?.let { service ->
        AlertDialog(
            onDismissRequest = { disconnect = null },
            title = { Text(stringResource(serviceName(service))) },
            text = { Text(stringResource(R.string.tasks_disconnect_text)) },
            confirmButton = {
                TextButton(onClick = {
                    disconnect = null
                    scope.launch {
                        persist { store.disconnect(service) }
                        show(R.string.tasks_disconnected)
                    }
                }) { Text(stringResource(R.string.tasks_disconnect)) }
            },
            dismissButton = { TextButton(onClick = { disconnect = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (googleSetup) {
        AlertDialog(
            onDismissRequest = { googleSetup = false },
            title = { Text(stringResource(R.string.tasks_google_setup_title)) },
            text = { Text(stringResource(R.string.tasks_google_setup_text), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { googleSetup = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

/** Личный ключ Todoist или TickTick: где взять и поле для него. */
@Composable
private fun KeyDialog(service: TaskService, onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(serviceName(service))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (service == TaskService.TODOIST) R.string.tasks_todoist_key_text else R.string.tasks_ticktick_key_text))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    label = { Text(stringResource(R.string.tasks_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = key.isNotBlank(), onClick = { onConnect(key) }) { Text(stringResource(R.string.tasks_connect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
