package com.dpsmpx.dpclock.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.AutoBackupPeriod
import com.dpsmpx.dpclock.model.AutoBackupRules
import com.dpsmpx.dpclock.persistLater
import com.dpsmpx.dpclock.platform.TimeText

private fun periodTitle(period: AutoBackupPeriod): Int = when (period) {
    AutoBackupPeriod.OFF -> R.string.auto_backup_off
    AutoBackupPeriod.DAILY -> R.string.auto_backup_daily
    AutoBackupPeriod.WEEKLY -> R.string.auto_backup_weekly
}

/**
 * Копия по расписанию в разделе «Перенос» (D-036, п. 4): как часто, в какую папку,
 * когда была последняя. Папка выбирается в системном окне — доступ только к ней.
 */
@Composable
fun AutoBackupSettings() {
    val context = LocalContext.current
    val store = DpClockApp.graph(context).autoBackup
    val state by store.state.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) persistLater { store.setFolder(uri) }
    }

    ListItem(
        headlineContent = { Text(stringResource(R.string.auto_backup)) },
        supportingContent = { Text(stringResource(periodTitle(state.period))) },
        modifier = Modifier.clickable { choosing = true },
    )
    if (state.period != AutoBackupPeriod.OFF) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.auto_backup_folder)) },
            supportingContent = {
                val status = when {
                    state.folder == null -> stringResource(R.string.auto_backup_no_folder)
                    state.failed -> stringResource(R.string.auto_backup_failed)
                    state.lastAt > 0 -> stringResource(R.string.auto_backup_last, TimeText.fullDateTime(context, state.lastAt), AutoBackupRules.KEEP)
                    else -> stringResource(R.string.auto_backup_pending)
                }
                Text(
                    if (state.folderName.isBlank()) status else state.folderName + "\n" + status,
                    color = if (state.folder == null || state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.clickable { pickFolder.launch(state.folder) },
        )
    }
    if (choosing) {
        ChoiceDialog(
            title = stringResource(R.string.auto_backup),
            choices = AutoBackupPeriod.entries,
            selected = state.period,
            label = { stringResource(periodTitle(it)) },
            onDismiss = { choosing = false },
            onSelect = { period -> persistLater { store.setPeriod(period) } },
        )
    }
}
