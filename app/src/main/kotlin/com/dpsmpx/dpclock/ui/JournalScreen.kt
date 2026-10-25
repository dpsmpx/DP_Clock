package com.dpsmpx.dpclock.ui

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.SignalEvent
import com.dpsmpx.dpclock.model.SignalOutcome
import com.dpsmpx.dpclock.model.SignalSource
import com.dpsmpx.dpclock.platform.TimeText
import java.time.Instant
import java.time.ZoneId

/** Опоздание больше минуты — заметно: выделяется цветом. */
private const val NOTICEABLE_MILLIS = 60_000L

/** Сколько записей уходит в «Поделиться»: хватает на неделю будильников и таймеров. */
private const val SHARE_LIMIT = 200

/**
 * Журнал срабатываний (0.7.1): когда звонили будильники и таймеры и на сколько
 * опоздали — проверка надёжности без ручных записей.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val events by DpClockApp.graph(context).repository.events.collectAsStateWithLifecycle(emptyList())

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { TitleText(stringResource(R.string.journal)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.back)) }
                    },
                    actions = {
                        if (events.isNotEmpty()) {
                            IconButton(onClick = { context.shareJournal(resources, events) }) {
                                Icon(AppIcons.Share, contentDescription = stringResource(R.string.journal_share))
                            }
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item {
                    Text(
                        stringResource(R.string.journal_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (events.isEmpty()) {
                    item { Text(stringResource(R.string.journal_empty), modifier = Modifier.padding(16.dp)) }
                }
                items(events, key = { it.id }) { event ->
                    val late = event.lateMillis
                    ListItem(
                        headlineContent = { Text(eventTitle(resources, event)) },
                        supportingContent = { Text(scheduledText(context, event) + " · " + resources.getString(outcomeText(event.outcome))) },
                        trailingContent = {
                            if (late != null) {
                                Text(
                                    lateText(resources, late),
                                    color = if (late > NOTICEABLE_MILLIS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun eventTitle(resources: Resources, event: SignalEvent): String = when (event.source) {
    SignalSource.ALARM ->
        if (event.title.isBlank()) resources.getString(R.string.journal_alarm) else resources.getString(R.string.journal_alarm_named, event.title)
    SignalSource.TIMER ->
        if (event.title.isBlank()) resources.getString(R.string.journal_timer) else resources.getString(R.string.journal_timer_named, event.title)
}

/** Расчётное время сигнала: дата и время. */
private fun scheduledText(context: Context, event: SignalEvent): String {
    val date = Instant.ofEpochMilli(event.scheduledAt).atZone(ZoneId.systemDefault()).toLocalDate()
    return TimeText.shortDate(context, date) + ", " + TimeText.time(context, event.scheduledAt)
}

private fun outcomeText(outcome: SignalOutcome): Int = when (outcome) {
    SignalOutcome.DISMISSED -> R.string.outcome_dismissed
    SignalOutcome.SNOOZED -> R.string.outcome_snoozed
    SignalOutcome.AUTO_STOPPED -> R.string.outcome_auto_stopped
    SignalOutcome.MISSED -> R.string.outcome_missed
}

/** Опоздание: «вовремя» до секунды, дальше «+3 с», «+2 мин 5 с», «+1 ч 4 мин». */
fun lateText(resources: Resources, millis: Long): String {
    val seconds = millis / 1000
    return when {
        seconds < 1 -> resources.getString(R.string.late_on_time)
        seconds < 60 -> resources.getString(R.string.late_seconds, seconds.toInt())
        seconds < 3600 -> resources.getString(R.string.late_minutes, (seconds / 60).toInt(), (seconds % 60).toInt())
        else -> resources.getString(R.string.late_hours, (seconds / 3600).toInt(), (seconds % 3600 / 60).toInt())
    }
}

private fun Context.shareJournal(resources: Resources, events: List<SignalEvent>) {
    val lines = events.take(SHARE_LIMIT).joinToString("\n") { event ->
        val late = event.lateMillis?.let { ", " + lateText(resources, it) }.orEmpty()
        scheduledText(this, event) + " — " + eventTitle(resources, event) + ": " + resources.getString(outcomeText(event.outcome)) + late
    }
    val text = resources.getString(R.string.journal_share_title) + "\n" + lines
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, resources.getString(R.string.journal_share)))
}
