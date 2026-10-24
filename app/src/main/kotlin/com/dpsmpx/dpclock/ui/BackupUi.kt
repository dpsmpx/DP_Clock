package com.dpsmpx.dpclock.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpclock.BuildConfig
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.Backup
import com.dpsmpx.dpclock.model.BackupException
import com.dpsmpx.dpclock.model.BackupFormat
import com.dpsmpx.dpclock.persist
import com.dpsmpx.dpclock.platform.TimeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** «Сохранить в файл» и «Загрузить из файла» (D-018). */
class BackupActions(val save: () -> Unit, val load: () -> Unit)

/**
 * Окна выбора файла, вопрос при загрузке и сообщения об итоге. Файл выбирает
 * человек в системном окне — разрешения на память телефона не нужно.
 */
@Composable
fun rememberBackupActions(snackbar: SnackbarHostState): BackupActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val repository = DpClockApp.graph(context).repository
    var loaded by remember { mutableStateOf<Backup?>(null) }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }

    fun show(text: Int) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(resources.getString(text))
        }
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFormat.MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = runCatching {
                val backup = persist { repository.exportBackup() }
                val text = BackupFormat.encode(backup, Instant.now(), BuildConfig.VERSION_NAME)
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("нет потока")
                    output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
            }.isSuccess
            show(if (saved) R.string.backup_saved else R.string.backup_save_failed)
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { readBackup(context, uri) } }
                .onSuccess { loaded = it }
                .onFailure { error = errorText(it) }
        }
    }

    loaded?.let { backup ->
        LoadDialog(
            backup = backup,
            onDismiss = { loaded = null },
            onLoad = { replace, withSettings ->
                loaded = null
                scope.launch {
                    val done = runCatching { persist { repository.importBackup(backup, replace, withSettings) } }.isSuccess
                    show(if (done) R.string.backup_loaded else R.string.backup_load_failed)
                }
            },
        )
    }
    error?.let { text ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(stringResource(R.string.backup_load_failed)) },
            text = { Text(stringResource(text)) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.ok)) } },
        )
    }

    return remember(create, open) {
        BackupActions(
            save = { create.launch(BackupFormat.fileName(LocalDate.now())) },
            // Тип JSON-файла файловые менеджеры и мессенджеры указывают по-разному; содержимое проверяется при чтении.
            load = { open.launch(arrayOf("*/*")) },
        )
    }
}

private class TooLarge : IOException()

private fun readBackup(context: Context, uri: Uri): Backup {
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("нет потока")
    val bytes = input.use {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val read = it.read(chunk)
            if (read < 0) break
            out.write(chunk, 0, read)
            if (out.size() > BackupFormat.MAX_BYTES) throw TooLarge()
        }
        out.toByteArray()
    }
    return BackupFormat.decode(bytes.toString(Charsets.UTF_8))
}

private fun errorText(error: Throwable): Int = when {
    error is TooLarge -> R.string.backup_too_large
    error !is BackupException -> R.string.backup_unreadable
    else -> when (error.reason) {
        BackupException.Reason.NOT_DP_CLOCK -> R.string.backup_not_dp_clock
        BackupException.Reason.NEWER_FORMAT -> R.string.backup_newer
        BackupException.Reason.BROKEN -> R.string.backup_broken
    }
}

/** Что в файле и как загрузить (выбор автора — спросить при загрузке, D-018, п. 4). */
@Composable
private fun LoadDialog(backup: Backup, onDismiss: () -> Unit, onLoad: (replace: Boolean, withSettings: Boolean) -> Unit) {
    val context = LocalContext.current
    var replace by rememberSaveable { mutableStateOf(false) }
    var withSettings by rememberSaveable { mutableStateOf(true) }
    val contents = listOfNotNull(
        backup.alarms.size.takeIf { it > 0 }?.let { stringResource(R.string.backup_alarms, it) },
        backup.groups.size.takeIf { it > 0 }?.let { stringResource(R.string.backup_groups, it) },
        backup.templates.size.takeIf { it > 0 }?.let { stringResource(R.string.backup_templates, it) },
        backup.exceptions.size.takeIf { it > 0 }?.let { stringResource(R.string.backup_exceptions, it) },
        backup.presets.size.takeIf { it > 0 }?.let { stringResource(R.string.backup_presets, it) },
        backup.settings?.let { stringResource(R.string.backup_settings) },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_load)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                backup.exportedAt?.let { at ->
                    val date = TimeText.date(context, at.atZone(ZoneId.systemDefault()).toLocalDate(), withYear = true)
                    Text(
                        if (backup.appVersion.isEmpty()) stringResource(R.string.backup_file_from, date)
                        else stringResource(R.string.backup_file_from_version, date, backup.appVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (contents.isEmpty()) {
                    Text(stringResource(R.string.backup_empty), modifier = Modifier.padding(top = 8.dp))
                } else {
                    Text(contents.joinToString("\n"), modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                    LoadChoices(backup, replace, { replace = it }, withSettings, { withSettings = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onLoad(replace, withSettings) }, enabled = contents.isNotEmpty()) {
                Text(stringResource(R.string.backup_load_button))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun LoadChoices(
    backup: Backup,
    replace: Boolean,
    onReplace: (Boolean) -> Unit,
    withSettings: Boolean,
    onWithSettings: (Boolean) -> Unit,
) {
    Choice(
        selected = !replace,
        title = stringResource(R.string.backup_add),
        text = stringResource(R.string.backup_add_text),
        onClick = { onReplace(false) },
    )
    Choice(
        selected = replace,
        title = stringResource(R.string.backup_replace),
        text = stringResource(R.string.backup_replace_text),
        onClick = { onReplace(true) },
    )
    if (backup.settings != null) {
        Row(
            Modifier.fillMaxWidth().clickable { onWithSettings(!withSettings) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = withSettings, onCheckedChange = null)
            Text(stringResource(R.string.backup_with_settings), modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun Choice(selected: Boolean, title: String, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
