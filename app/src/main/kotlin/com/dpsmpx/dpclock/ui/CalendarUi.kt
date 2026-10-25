package com.dpsmpx.dpclock.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.CalendarInstance
import com.dpsmpx.dpclock.platform.PhoneCalendar
import com.dpsmpx.dpclock.platform.PhoneCalendarInfo
import com.dpsmpx.dpclock.platform.TimeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Доступ к календарю телефона (D-023, п. 1): спрашивается, только когда выбирают календари. */
class CalendarAccess(val granted: Boolean, private val ask: (() -> Unit) -> Unit) {
    /** Есть доступ — сразу [then]; нет — спросить, и [then], если дали. */
    fun request(then: () -> Unit) = ask(then)
}

@Composable
fun rememberCalendarAccess(): CalendarAccess {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(PhoneCalendar.granted(context)) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) pending?.invoke()
        pending = null
    }
    return CalendarAccess(granted) { then ->
        if (PhoneCalendar.granted(context)) {
            granted = true
            then()
        } else {
            pending = then
            launcher.launch(Manifest.permission.READ_CALENDAR)
        }
    }
}

/** Календари телефона; без доступа — пусто. Читается вне главного потока. */
@Composable
fun rememberPhoneCalendars(granted: Boolean): List<PhoneCalendarInfo> {
    val context = LocalContext.current
    val calendars by produceState(emptyList<PhoneCalendarInfo>(), granted) {
        value = if (granted) withContext(Dispatchers.IO) { PhoneCalendar.calendars(context) } else emptyList()
    }
    return calendars
}

/** Названия выбранных календарей через запятую; не выбраны — [empty]. */
@Composable
fun calendarNames(ids: Set<Long>, calendars: List<PhoneCalendarInfo>, empty: String): String {
    if (ids.isEmpty()) return empty
    val names = calendars.filter { it.id in ids }.map { it.name.ifBlank { it.account } }
    return names.joinToString(", ").ifEmpty { stringResource(R.string.calendar_selected_count, ids.size) }
}

/** Выбор календарей галочками: цвет календаря, название, аккаунт. */
@Composable
fun CalendarChooserDialog(title: String, calendars: List<PhoneCalendarInfo>, selected: Set<Long>, onDismiss: () -> Unit, onSave: (Set<Long>) -> Unit) {
    // LongArray — его сохраняет поворот экрана.
    var chosen by rememberSaveable { mutableStateOf(selected.toLongArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { TitleText(title) },
        text = {
            if (calendars.isEmpty()) {
                Text(stringResource(R.string.calendar_none_found))
            } else {
                LazyColumn {
                    items(calendars, key = { it.id }) { calendar ->
                        val checked = calendar.id in chosen
                        ListItem(
                            leadingContent = { Box(Modifier.size(16.dp).clip(CircleShape).background(Color(calendar.color))) },
                            headlineContent = { Text(calendar.name.ifBlank { calendar.account }) },
                            supportingContent = { if (calendar.account.isNotBlank()) Text(calendar.account) },
                            trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                            modifier = Modifier.toggleable(value = checked, role = Role.Checkbox) { on ->
                                chosen = if (on) chosen + calendar.id else chosen.filter { it != calendar.id }.toLongArray()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(chosen.toSet()) }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Настройки приложения в Android — вернуть отнятый доступ. */
fun Context.openAppSettings() {
    runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())) }
}

/** Ближайшее событие сегодня (D-023, п. 4): перечитывается раз в минуту; выключено или нет доступа — null. */
@Composable
fun rememberNextEvent(active: Boolean = true): CalendarInstance? {
    val context = LocalContext.current
    val settings by DpClockApp.graph(context).calendar.settings.collectAsStateWithLifecycle()
    val event by produceState<CalendarInstance?>(null, settings, active) {
        if (!active || !settings.showNext || settings.eventCalendars.isEmpty()) {
            value = null
            return@produceState
        }
        while (true) {
            value = withContext(Dispatchers.IO) { PhoneCalendar.nextEvent(context) }
            delay(60_000L)
        }
    }
    return event
}

/** События календарей событий в дату [date] (D-023, п. 5); null — ничего не читать. */
@Composable
fun rememberDayEvents(date: LocalDate?): List<CalendarInstance> {
    val context = LocalContext.current
    val settings by DpClockApp.graph(context).calendar.settings.collectAsStateWithLifecycle()
    val events by produceState(emptyList<CalendarInstance>(), date, settings) {
        value = if (date == null || settings.eventCalendars.isEmpty()) emptyList() else withContext(Dispatchers.IO) { PhoneCalendar.eventsOn(context, date) }
    }
    return events
}

/** Событие строкой: «10:00 · Планёрка», «Весь день · Отпуск»; [withTitle] = false — без названия. */
fun eventText(context: Context, event: CalendarInstance, withTitle: Boolean = true): String {
    val time = if (event.allDay) context.getString(R.string.calendar_all_day) else TimeText.time(context, event.begin)
    val title = event.title.ifBlank { context.getString(R.string.calendar_untitled) }
    return if (withTitle) context.getString(R.string.calendar_event_line, time, title) else context.getString(R.string.calendar_event_hidden, time)
}
