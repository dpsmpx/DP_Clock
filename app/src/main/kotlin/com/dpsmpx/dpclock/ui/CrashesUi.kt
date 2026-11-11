package com.dpsmpx.dpclock.ui

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.platform.Crashes

/**
 * Журнал сбоев (D-036, п. 2): последние сбои приложения, свежие сверху, —
 * «Поделиться» текстом и «Очистить». Наружу журнал уходит только так.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val log = remember { Crashes.log(context) }
    var entries by remember { mutableStateOf(log.entries().reversed()) }
    val shareTitle = stringResource(R.string.crashes_share)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { TitleText(stringResource(R.string.crashes)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.back)) }
                    },
                    actions = {
                        if (entries.isNotEmpty()) {
                            IconButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, entries.joinToString("\n\n"))
                                context.startActivity(Intent.createChooser(send, shareTitle))
                            }) { Icon(AppIcons.Share, contentDescription = shareTitle) }
                            TextButton(onClick = {
                                log.clear()
                                entries = emptyList()
                            }) { Text(stringResource(R.string.crashes_clear)) }
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item {
                    Text(
                        stringResource(R.string.crashes_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (entries.isEmpty()) {
                    item { Text(stringResource(R.string.crashes_empty), modifier = Modifier.padding(16.dp)) }
                }
                items(entries) { entry ->
                    Text(entry.lineSequence().first(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                    Text(
                        // В списке — начало трассы; целиком — в «Поделиться».
                        entry.lineSequence().drop(1).take(TRACE_LINES).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private const val TRACE_LINES = 12
