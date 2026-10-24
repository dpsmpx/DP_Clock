package com.dpsmpx.dpclock.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.LapRow
import com.dpsmpx.dpclock.model.Stopwatch
import com.dpsmpx.dpclock.model.StopwatchState
import com.dpsmpx.dpclock.persistLater
import com.dpsmpx.dpclock.platform.Clocks
import com.dpsmpx.dpclock.platform.TimeText
import java.util.Locale

/** Раздел «Секундомер» (SCREEN_MAP.md, п. 7). Идёт и при свёрнутом приложении: время считается от момента старта. */
@Composable
fun StopwatchScreen() {
    val repository = DpClockApp.graph(LocalContext.current).repository
    val stopwatch by repository.stopwatch.collectAsStateWithLifecycle(Stopwatch())
    val now = rememberMoment(33L, active = stopwatch.state == StopwatchState.RUNNING)
    val locale = LocalConfiguration.current.locales[0]
    val elapsed = stopwatch.elapsedAt(now)

    fun update(change: (Stopwatch) -> Stopwatch) = persistLater { repository.updateStopwatch(change) }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            TimeText.stopwatch(elapsed, locale),
            style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            val big = Modifier.size(72.dp)
            when (stopwatch.state) {
                StopwatchState.RUNNING -> {
                    FilledTonalIconButton(onClick = { update { it.lap(Clocks.moment()) } }, modifier = big) {
                        Icon(AppIcons.Lap, contentDescription = stringResource(R.string.lap))
                    }
                    FilledIconButton(
                        onClick = { update { it.pause(Clocks.moment()) } },
                        modifier = big,
                        colors = IconButtonDefaults.filledIconButtonColors(),
                    ) { Icon(AppIcons.Pause, contentDescription = stringResource(R.string.pause)) }
                }
                StopwatchState.PAUSED -> {
                    FilledTonalIconButton(onClick = { update { it.reset() } }, modifier = big) {
                        Icon(AppIcons.Reset, contentDescription = stringResource(R.string.reset))
                    }
                    FilledIconButton(onClick = { update { it.start(Clocks.moment()) } }, modifier = big) {
                        Icon(AppIcons.Play, contentDescription = stringResource(R.string.resume))
                    }
                }
                StopwatchState.RESET -> FilledIconButton(onClick = { update { it.start(Clocks.moment()) } }, modifier = big) {
                    Icon(AppIcons.Play, contentDescription = stringResource(R.string.start))
                }
            }
        }
        Spacer(Modifier.size(24.dp))
        val rows = stopwatch.lapRows()
        if (rows.isNotEmpty()) {
            val context = LocalContext.current
            TextButton(onClick = { context.shareLaps(rows, elapsed, locale) }) {
                Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.share_laps))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.lap_number), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.lap_time), Modifier.weight(2f), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
                Text(stringResource(R.string.lap_total), Modifier.weight(2f), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp)) {
            items(rows, key = { it.number }) { row ->
                val color = when {
                    row.isBest -> MaterialTheme.colorScheme.primary
                    row.isWorst -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val mark = when {
                    row.isBest -> " · " + stringResource(R.string.lap_best)
                    row.isWorst -> " · " + stringResource(R.string.lap_worst)
                    else -> ""
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text("${row.number}$mark", Modifier.weight(1f), color = color)
                    Text(TimeText.stopwatch(row.lapMillis, locale), Modifier.weight(2f), color = color, textAlign = TextAlign.End)
                    Text(TimeText.stopwatch(row.totalMillis, locale), Modifier.weight(2f), textAlign = TextAlign.End)
                }
            }
        }
    }
}

/** «Поделиться кругами» (D-011, п. 6): текстом через системное окно — копировать, сообщение, заметки. */
private fun Context.shareLaps(rows: List<LapRow>, total: Long, locale: Locale) {
    val lines = rows.sortedBy { it.number }.joinToString("\n") { row ->
        val mark = when {
            row.isBest -> " — " + getString(R.string.lap_best)
            row.isWorst -> " — " + getString(R.string.lap_worst)
            else -> ""
        }
        getString(R.string.share_lap_row, row.number, TimeText.stopwatch(row.lapMillis, locale), TimeText.stopwatch(row.totalMillis, locale)) + mark
    }
    val text = getString(R.string.share_laps_title) + "\n" +
        getString(R.string.share_laps_total, TimeText.stopwatch(total, locale)) + "\n" + lines
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, getString(R.string.share_laps)))
}
