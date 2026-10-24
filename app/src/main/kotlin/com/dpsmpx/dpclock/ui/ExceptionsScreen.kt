package com.dpsmpx.dpclock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.dpsmpx.dpclock.model.ExceptionPeriod
import com.dpsmpx.dpclock.model.Exceptions
import com.dpsmpx.dpclock.model.RuCalendar
import com.dpsmpx.dpclock.persistLater
import com.dpsmpx.dpclock.platform.TimeText
import java.time.LocalDate
import java.util.UUID

private const val DAY_MILLIS = 86_400_000L

/** Ключ окна «новый период». */
private const val NEW = "new"

/**
 * Общий список исключений (D-014, п. 4): праздники и переносы производственного
 * календаря России — переключателем, свои даты и периоды — списком.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExceptionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = DpClockApp.graph(context).repository
    val exceptions by repository.exceptions.collectAsStateWithLifecycle(Exceptions.NONE)
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val years = RuCalendar.decreedYears.sorted()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.exceptions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = stringResource(R.string.back)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = NEW },
                icon = { Icon(AppIcons.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_period)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.holidays_ru)) },
                    supportingContent = { Text(stringResource(R.string.holidays_ru_text, years.first(), years.last())) },
                    trailingContent = {
                        Switch(
                            checked = exceptions.holidays,
                            onCheckedChange = { on -> persistLater { repository.updateSettings { it.copy(holidaysRu = on) } } },
                        )
                    },
                )
            }
            if (exceptions.holidays) {
                item {
                    val ranges = upcomingDaysOff(LocalDate.now(), 4)
                    Text(
                        stringResource(R.string.holidays_upcoming, ranges.joinToString(", ") { (start, end) -> TimeText.dateRange(context, start, end) }),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.own_periods),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                )
            }
            if (exceptions.periods.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.periods_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            items(exceptions.periods, key = { it.id }) { period ->
                val past = period.end.isBefore(LocalDate.now())
                ListItem(
                    headlineContent = { Text(period.name.ifBlank { stringResource(R.string.untitled) }) },
                    supportingContent = {
                        Text(
                            TimeText.dateRange(context, period.start, period.end),
                            color = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { persistLater { repository.deleteException(period.id) } }) {
                            Icon(AppIcons.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    },
                    modifier = Modifier.clickable { editing = period.id },
                )
            }
            item {
                Text(
                    stringResource(R.string.exceptions_footnote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                )
            }
        }
    }

    val key = editing
    if (key != null) {
        PeriodDialog(
            initial = exceptions.periods.firstOrNull { it.id == key },
            onDismiss = { editing = null },
            onSave = { period ->
                editing = null
                persistLater { repository.saveException(period) }
            },
        )
    }
}

/** Период исключения: название и даты с — по; одна дата — начало без конца. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodDialog(initial: ExceptionPeriod?, onDismiss: () -> Unit, onSave: (ExceptionPeriod) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.start?.toEpochDay()?.times(DAY_MILLIS),
        initialSelectedEndDateMillis = initial?.end?.toEpochDay()?.times(DAY_MILLIS),
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.period)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.cancel)) }
                    },
                    actions = {
                        val start = state.selectedStartDateMillis
                        TextButton(enabled = start != null, onClick = {
                            val first = LocalDate.ofEpochDay(start!! / DAY_MILLIS)
                            val last = state.selectedEndDateMillis?.let { LocalDate.ofEpochDay(it / DAY_MILLIS) } ?: first
                            onSave(ExceptionPeriod(initial?.id ?: UUID.randomUUID().toString(), name.trim(), first, last))
                        }) { Text(stringResource(R.string.save)) }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(ExceptionPeriod.MAX_NAME) },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                DateRangePicker(state, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Ближайшие подряд идущие официальные выходные дни, не больше [count] отрезков, в пределах года. */
private fun upcomingDaysOff(from: LocalDate, count: Int): List<Pair<LocalDate, LocalDate>> {
    val ranges = mutableListOf<Pair<LocalDate, LocalDate>>()
    var date = from
    val until = from.plusDays(366)
    while (date.isBefore(until) && ranges.size < count) {
        if (RuCalendar.isDayOff(date)) {
            val start = date
            while (RuCalendar.isDayOff(date.plusDays(1))) date = date.plusDays(1)
            ranges += start to date
        }
        date = date.plusDays(1)
    }
    return ranges
}
