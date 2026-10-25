package com.dpsmpx.dpclock.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.AfterAlarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Приложение телефона для «открыть после будильника». */
data class LaunchableApp(val packageName: String, val label: String)

/** Приложения с иконкой на рабочем столе, кроме DP Clock, по названию. */
fun Context.launchableApps(): List<LaunchableApp> {
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packageManager.queryIntentActivities(launcher, 0)
        .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
        .filter { it.packageName != packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

/** Название приложения по пакету; не установлено — сам пакет. */
fun Context.appLabel(packageName: String): String = runCatching {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName)

/**
 * Что сделать после выключения будильника (D-017, п. 5): запустить пресет
 * таймера, напомнить своим текстом сразу или через N минут, открыть приложение.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AfterAlarmEditor(initial: AfterAlarm, onDismiss: () -> Unit, onDone: (AfterAlarm) -> Unit) {
    val context = LocalContext.current
    val presets by DpClockApp.graph(context).repository.presets.collectAsStateWithLifecycle(emptyList())
    var after by remember { mutableStateOf(initial) }
    var pickApp by rememberSaveable { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { TitleText(stringResource(R.string.after_alarm)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.cancel)) }
                    },
                    actions = { TextButton(onClick = { onDone(after) }) { Text(stringResource(R.string.done)) } },
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding()) {
                item { SectionTitle(R.string.after_timer) }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.after_none)) },
                        leadingContent = { RadioButton(selected = after.presetId == null, onClick = null) },
                        modifier = Modifier.clickable { after = after.copy(presetId = null) },
                    )
                }
                if (presets.isEmpty()) {
                    item { Hint(R.string.after_no_presets) }
                }
                items(presets, key = { "preset:" + it.id }) { preset ->
                    ListItem(
                        headlineContent = { Text(preset.name) },
                        leadingContent = { RadioButton(selected = after.presetId == preset.id, onClick = null) },
                        modifier = Modifier.clickable { after = after.copy(presetId = preset.id) },
                    )
                }
                item { SectionTitle(R.string.after_reminder) }
                item {
                    OutlinedTextField(
                        value = after.reminder,
                        onValueChange = { after = after.copy(reminder = it.take(AfterAlarm.MAX_REMINDER)) },
                        label = { Text(stringResource(R.string.after_reminder_text)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
                if (after.reminder.isNotBlank()) {
                    item {
                        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (minutes in AfterAlarm.REMINDER_DELAYS) {
                                FilterChip(
                                    selected = after.reminderMinutes == minutes,
                                    onClick = { after = after.copy(reminderMinutes = minutes) },
                                    label = {
                                        Text(if (minutes == 0) stringResource(R.string.after_now) else stringResource(R.string.after_in_minutes, minutes))
                                    },
                                )
                            }
                        }
                    }
                }
                item { SectionTitle(R.string.after_app) }
                item {
                    ListItem(
                        headlineContent = {
                            Text(if (after.app.isEmpty()) stringResource(R.string.after_none) else context.appLabel(after.app))
                        },
                        supportingContent = { Text(stringResource(R.string.after_app_hint)) },
                        trailingContent = {
                            if (after.app.isNotEmpty()) {
                                IconButton(onClick = { after = after.copy(app = "") }) {
                                    Icon(AppIcons.Close, contentDescription = stringResource(R.string.delete))
                                }
                            }
                        },
                        modifier = Modifier.clickable { pickApp = true },
                    )
                }
            }
        }
    }

    if (pickApp) {
        AppPicker(onDismiss = { pickApp = false }, onPick = {
            after = after.copy(app = it)
            pickApp = false
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val apps by produceState<List<LaunchableApp>?>(null) { value = withContext(Dispatchers.IO) { context.launchableApps() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { TitleText(stringResource(R.string.after_app)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.cancel)) }
                    },
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(apps.orEmpty(), key = { it.packageName }) { app ->
                    ListItem(headlineContent = { Text(app.label) }, modifier = Modifier.clickable { onPick(app.packageName) })
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** Кратко для редактора будильника. */
@Composable
fun afterSummary(after: AfterAlarm, presetName: String?): String {
    val context = LocalContext.current
    if (after.isEmpty) return stringResource(R.string.after_none)
    return listOfNotNull(
        presetName,
        after.reminder.takeIf { it.isNotBlank() },
        after.app.takeIf { it.isNotEmpty() }?.let { context.appLabel(it) },
    ).joinToString(" · ")
}
