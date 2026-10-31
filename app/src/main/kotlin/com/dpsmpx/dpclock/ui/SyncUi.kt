package com.dpsmpx.dpclock.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.SyncChoice
import com.dpsmpx.dpclock.model.SyncConflict
import com.dpsmpx.dpclock.model.SyncFormat
import com.dpsmpx.dpclock.model.SyncItems
import com.dpsmpx.dpclock.persist
import com.dpsmpx.dpclock.persistLater
import com.dpsmpx.dpclock.platform.SyncError
import com.dpsmpx.dpclock.platform.SyncProbe
import com.dpsmpx.dpclock.platform.TimeText
import com.dpsmpx.dpclock.platform.ZoneNames
import kotlinx.coroutines.launch

/**
 * Раздел настроек «Синхронизация» (D-030): общий файл в облаке, «Синхронизировать
 * сейчас», состояние и расхождения.
 */
@Composable
fun SyncSettings(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val cloud = DpClockApp.graph(context).cloud
    val status by cloud.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    /** Выбранный файл с данными — спросить, объединить или взять из файла. */
    var first by remember { mutableStateOf<SyncProbe.Ready?>(null) }
    var conflictsOpen by rememberSaveable { mutableStateOf(false) }

    fun show(text: Int) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(resources.getString(text))
        }
    }

    fun connect(file: SyncProbe.Ready, replace: Boolean) {
        scope.launch {
            val done = persist { DpClockApp.graph(context).cloud.connect(file, replace) }
            show(if (done) R.string.sync_done else R.string.sync_failed)
        }
    }

    fun chosen(uri: android.net.Uri?, created: Boolean) {
        if (uri == null) return
        scope.launch {
            when (val probe = persist { DpClockApp.graph(context).cloud.probe(uri) }) {
                is SyncProbe.Failed -> show(errorText(probe.error))
                is SyncProbe.Ready -> if (created || probe.items == 0) connect(probe, replace = false) else first = probe
            }
        }
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SyncFormat.MIME)) { chosen(it, created = true) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen(it, created = false) }
    // Облако может назвать файл JSON и текстом, и просто данными.
    val types = arrayOf(SyncFormat.MIME, "text/plain", "application/octet-stream")

    Text(
        stringResource(R.string.sync_text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    val file = status.file
    if (file == null) {
        SettingsRow(stringResource(R.string.sync_create), stringResource(R.string.sync_create_text)) { create.launch(SyncFormat.FILE_NAME) }
        SettingsRow(stringResource(R.string.sync_choose), stringResource(R.string.sync_choose_text)) { choose.launch(types) }
    } else {
        val state = when {
            status.running -> stringResource(R.string.sync_running)
            status.error != null -> stringResource(errorText(status.error!!))
            status.lastAt > 0 -> stringResource(R.string.sync_last, whenText(context, status.lastAt))
            else -> stringResource(R.string.sync_never)
        }
        SettingsRow(stringResource(R.string.sync_now), state) {
            scope.launch {
                val done = persist { DpClockApp.graph(context).cloud.run() }
                show(if (done) R.string.sync_done else R.string.sync_failed)
            }
        }
        if (status.conflicts.isNotEmpty()) {
            SettingsRow(stringResource(R.string.sync_conflicts_title), pluralStringResource(R.plurals.sync_conflicts_count, status.conflicts.size, status.conflicts.size)) {
                conflictsOpen = true
            }
        }
        SettingsRow(stringResource(R.string.sync_file), file) { choose.launch(types) }
        SettingsRow(stringResource(R.string.sync_off), stringResource(R.string.sync_off_text)) {
            scope.launch {
                persist { DpClockApp.graph(context).cloud.disconnect() }
                show(R.string.sync_off_done)
            }
        }
    }

    first?.let { probe ->
        AlertDialog(
            onDismissRequest = {
                first = null
                cloud.cancel(probe)
            },
            title = { Text(stringResource(R.string.sync_first_title)) },
            text = { Text(pluralStringResource(R.plurals.sync_first_text, probe.alarms, probe.alarms)) },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        first = null
                        connect(probe, replace = true)
                    }) { Text(stringResource(R.string.sync_first_replace)) }
                    TextButton(onClick = {
                        first = null
                        connect(probe, replace = false)
                    }) { Text(stringResource(R.string.sync_first_merge)) }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    first = null
                    cloud.cancel(probe)
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (conflictsOpen && status.conflicts.isNotEmpty()) SyncConflictsDialog(status.conflicts) { conflictsOpen = false }
}

@Composable
private fun SettingsRow(title: String, text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Плашка над будильниками: на двух телефонах поправили одно и то же — выбрать версию. */
@Composable
fun SyncConflictsBanner() {
    val cloud = DpClockApp.graph(LocalContext.current).cloud
    val status by cloud.status.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val conflicts = status.conflicts
    if (conflicts.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sync_conflicts_title), style = MaterialTheme.typography.titleSmall)
            Text(pluralStringResource(R.plurals.sync_conflicts_count, conflicts.size, conflicts.size), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = { open = true }) { Text(stringResource(R.string.sync_conflicts_choose)) }
        }
    }
    if (open) SyncConflictsDialog(conflicts) { open = false }
}

/**
 * Расхождения (выбор автора — «Спрашивать»): у каждого — версия этого телефона и
 * другого, с временем правки. Ответ сразу уходит в файл: другой телефон возьмёт его
 * без вопроса. Без ответа всё остаётся как есть.
 */
@Composable
fun SyncConflictsDialog(conflicts: List<SyncConflict>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val choices = remember { mutableStateMapOf<String, SyncChoice>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_conflicts_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.sync_conflicts_text), style = MaterialTheme.typography.bodyMedium)
                for (conflict in conflicts) {
                    Column {
                        Text(kindText(context, conflict.key), style = MaterialTheme.typography.titleSmall)
                        Choice(
                            selected = choices[conflict.key] == SyncChoice.LOCAL,
                            title = stringResource(R.string.sync_this_phone, whenText(context, conflict.localUpdatedAt)),
                            text = summary(context, conflict.key, conflict.local, conflict.remote),
                        ) { choices[conflict.key] = SyncChoice.LOCAL }
                        Choice(
                            selected = choices[conflict.key] == SyncChoice.REMOTE,
                            title = stringResource(
                                R.string.sync_other_phone,
                                conflict.remoteDevice.ifBlank { stringResource(R.string.sync_another_phone) },
                                whenText(context, conflict.remoteUpdatedAt),
                            ),
                            text = summary(context, conflict.key, conflict.remote, conflict.local),
                        ) { choices[conflict.key] = SyncChoice.REMOTE }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = choices.isNotEmpty(), onClick = {
                val answers = choices.toMap()
                onDismiss()
                persistLater { DpClockApp.graph(context).cloud.run(answers) }
            }) { Text(stringResource(R.string.sync_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sync_later)) } },
    )
}

@Composable
private fun Choice(selected: Boolean, title: String, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun errorText(error: SyncError): Int = when (error) {
    SyncError.UNAVAILABLE -> R.string.sync_error_unavailable
    SyncError.FOREIGN -> R.string.sync_error_foreign
    SyncError.WRITE -> R.string.sync_error_write
}

private fun whenText(context: Context, at: Long): String =
    DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

/** Что это: будильник, группа, шаблон… */
private fun kindText(context: Context, key: String): String = context.getString(
    when {
        key.startsWith(SyncItems.ALARM) -> R.string.sync_kind_alarm
        key.startsWith(SyncItems.GROUP) -> R.string.sync_kind_group
        key.startsWith(SyncItems.TEMPLATE) -> R.string.sync_kind_template
        key.startsWith(SyncItems.EXCEPTION) -> R.string.sync_kind_exception
        key.startsWith(SyncItems.PRESET) -> R.string.sync_kind_preset
        key == SyncItems.SETTINGS -> R.string.settings
        else -> R.string.settings_world
    },
)

/**
 * Версия коротко: у будильника — время, название и повтор; у настроек — разделы,
 * которыми она отличается от [other]. Не читается — пустая строка.
 */
private fun summary(context: Context, key: String, data: Map<String, Any?>?, other: Map<String, Any?>?): String {
    if (data == null) return context.getString(R.string.sync_deleted)
    return runCatching {
        when {
            key.startsWith(SyncItems.ALARM) -> SyncItems.alarm(data).let { alarm ->
                listOf(TimeText.time(context, alarm.hour, alarm.minute), alarm.label, repeatText(context, alarm.repeat)).filter { it.isNotBlank() }.joinToString(" · ")
            }
            key.startsWith(SyncItems.GROUP) -> SyncItems.group(data).name
            key.startsWith(SyncItems.TEMPLATE) -> SyncItems.template(data).let { template ->
                template.name + " · " + context.resources.getQuantityString(R.plurals.alarms_count, template.alarms.size, template.alarms.size)
            }
            key.startsWith(SyncItems.EXCEPTION) -> SyncItems.exception(data).let { period ->
                period.name + " · " + TimeText.date(context, period.start) + " – " + TimeText.date(context, period.end)
            }
            key.startsWith(SyncItems.PRESET) -> SyncItems.preset(data).let { preset -> preset.name + " · " + durationText(context.resources, preset.program.totalMillis) }
            key == SyncItems.SETTINGS -> {
                val sections = data.keys.filter { data[it] != other?.get(it) }.map { SETTINGS_SECTIONS[it] ?: R.string.settings_other }.distinct()
                sections.joinToString(", ") { context.getString(it) }
            }
            else -> SyncItems.worldCities(data).joinToString(", ") { ZoneNames.city(context, it) }.ifEmpty { context.getString(R.string.sync_no_cities) }
        }
    }.getOrDefault("")
}

/** Ключ настроек в файле → раздел настроек, в котором он виден. */
private val SETTINGS_SECTIONS = mapOf(
    "snoozeMinutes" to R.string.settings_alarms,
    "autoStopMinutes" to R.string.settings_alarms,
    "alarmOrder" to R.string.settings_alarms,
    "showAlarmCountdown" to R.string.settings_alarms,
    "showAlarmDate" to R.string.settings_alarms,
    "defaultWarnings" to R.string.settings_alarms,
    "warningSound" to R.string.settings_alarms,
    "holidaysRu" to R.string.settings_alarms,
    "chimes" to R.string.settings_chimes,
    "pomodoro" to R.string.pomodoro,
    "signalStyle" to R.string.settings_signal,
    "volumeButtons" to R.string.settings_signal,
    "bedtime" to R.string.settings_sleep,
    "appearance" to R.string.settings_appearance,
    "haptics" to R.string.settings_appearance,
    "weather" to R.string.settings_world,
)
