package com.dpsmpx.dpclock.ui

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.data.SunPlace
import com.dpsmpx.dpclock.data.SunPlaceSource
import com.dpsmpx.dpclock.model.AlarmSound
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.SunEvent
import com.dpsmpx.dpclock.model.SunMoment
import com.dpsmpx.dpclock.model.SunRules
import com.dpsmpx.dpclock.model.SunSignal
import com.dpsmpx.dpclock.model.SunSignalKind
import com.dpsmpx.dpclock.model.SunSignals
import com.dpsmpx.dpclock.model.SunTimes
import com.dpsmpx.dpclock.persistLater
import com.dpsmpx.dpclock.platform.Places
import com.dpsmpx.dpclock.platform.SunLocation
import com.dpsmpx.dpclock.platform.TimeText
import com.dpsmpx.dpclock.platform.ZoneNames
import java.time.Instant
import java.time.ZoneId

/** «Восход через 5 мин», «Закат» — заголовок уведомления и экрана сигнала (D-035). */
fun sunTitle(context: Context, event: SunEvent, minutes: Int): String {
    val name = context.getString(if (event == SunEvent.SUNRISE) R.string.sun_sunrise else R.string.sun_sunset)
    return if (minutes == 0) name else context.getString(R.string.sun_in, name, leadText(context, minutes))
}

/** «За 5 мин до восхода», «В момент заката» — строка сигнала в настройках. */
private fun momentText(context: Context, event: SunEvent, minutes: Int): String = when {
    minutes == 0 -> context.getString(if (event == SunEvent.SUNRISE) R.string.sun_moment_at_sunrise else R.string.sun_moment_at_sunset)
    event == SunEvent.SUNRISE -> context.getString(R.string.sun_moment_before_sunrise, leadText(context, minutes))
    else -> context.getString(R.string.sun_moment_before_sunset, leadText(context, minutes))
}

private fun kindTitle(kind: SunSignalKind): Int = when (kind) {
    SunSignalKind.NOTIFICATION -> R.string.sun_kind_notification
    SunSignalKind.ALARM -> R.string.sun_kind_alarm
}

private fun kindText(kind: SunSignalKind): Int = when (kind) {
    SunSignalKind.NOTIFICATION -> R.string.sun_kind_notification_text
    SunSignalKind.ALARM -> R.string.sun_kind_alarm_text
}

/** Откуда место и как оно называется: «Тула · по геолокации». */
private fun placeText(context: Context, place: SunPlace?): String = when (place?.source) {
    null -> context.getString(R.string.sun_place_unknown)
    SunPlaceSource.LOCATION ->
        if (place.name.isBlank()) context.getString(R.string.sun_place_location_unnamed) else context.getString(R.string.sun_place_location, place.name)
    SunPlaceSource.WEATHER -> context.getString(R.string.sun_place_weather, place.name)
    SunPlaceSource.ZONE -> context.getString(R.string.sun_place_zone, ZoneNames.city(context, ZoneId.systemDefault().id))
}

/**
 * Раздел настроек «Восход и закат» (D-035): место, времена на сегодня, сигналы
 * восхода и заката — у каждого «за сколько» и чем: уведомлением или будильником, —
 * мелодия и вибрация будильника.
 */
@Composable
fun SunSettings(settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val store = DpClockApp.graph(context).sun
    val location by store.location.collectAsStateWithLifecycle()
    val sun = settings.sun
    // Место — по геолокации, городу погоды или поясу; геолокация меняется — раздел пересчитывается.
    val place = remember(location, settings.weather) { store.place(settings) }
    val zone = ZoneId.systemDefault()
    val now = rememberNowMillis(60_000L)
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }

    fun updateSun(change: (SunSignals) -> SunSignals) = update { it.copy(sun = change(it.sun)) }
    fun updateSignal(event: SunEvent, change: (SunSignal) -> SunSignal) = updateSun { it.with(event, change(it.of(event))) }

    var granted by remember { mutableStateOf(Places.hasLocationPermission(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        persistLater { SunLocation.refresh(app, force = true) }
    }
    // Раздел открыт — место обновляется, если геолокация разрешена.
    LaunchedEffect(Unit) { persistLater { SunLocation.refresh(app, force = true) } }

    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.sun_place)) },
            supportingContent = {
                Text(placeText(context, place) + "\n" + stringResource(if (granted) R.string.sun_place_refresh else R.string.sun_place_allow))
            },
            modifier = Modifier.clickable {
                if (granted) persistLater { SunLocation.refresh(app, force = true) } else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            },
        )
        if (place != null) {
            val today = remember(place, now / 3_600_000L, zone) {
                SunTimes.on(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), place.latitude, place.longitude, zone)
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.sun_today)) },
                supportingContent = {
                    Text(
                        listOf(
                            today.sunrise?.let { stringResource(R.string.sun_sunrise_at, TimeText.time(context, it.toEpochMilli())) } ?: stringResource(R.string.sun_no_sunrise),
                            today.sunset?.let { stringResource(R.string.sun_sunset_at, TimeText.time(context, it.toEpochMilli())) } ?: stringResource(R.string.sun_no_sunset),
                        ).joinToString(" · "),
                    )
                },
            )
            if (sun.enabled) {
                val next = remember(place, sun, now, zone) {
                    SunRules.next(sun, place.latitude, place.longitude, Instant.ofEpochMilli(now), zone).firstOrNull()
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.sun_next)) },
                    supportingContent = {
                        Text(
                            if (next == null) stringResource(R.string.sun_next_none)
                            else TimeText.fullDateTime(context, next.at.toEpochMilli()) + " — " + momentText(context, next.event, next.minutes),
                        )
                    },
                )
            }
        }
        for (event in SunEvent.entries) {
            val signal = sun.of(event)
            SwitchRow(
                title = stringResource(if (event == SunEvent.SUNRISE) R.string.sun_sunrise_signal else R.string.sun_sunset_signal),
                checked = signal.enabled,
                supporting = if (signal.enabled) null else stringResource(R.string.sun_signal_off),
            ) { on ->
                // Включили без единого сигнала — возвращаются «за 5 минут» и «в момент».
                updateSignal(event) { it.copy(enabled = on, moments = if (on && it.moments.isEmpty()) SunSignal.DEFAULT_MOMENTS else it.moments) }
            }
            if (signal.enabled) {
                for (moment in signal.moments) {
                    ListItem(
                        headlineContent = { Text(momentText(context, event, moment.minutes)) },
                        supportingContent = { Text(stringResource(kindTitle(moment.kind))) },
                        trailingContent = {
                            IconButton(onClick = { updateSignal(event) { it.copy(moments = it.moments.filter { m -> m.minutes != moment.minutes }) } }) {
                                Icon(AppIcons.Delete, contentDescription = stringResource(R.string.sun_remove))
                            }
                        },
                        modifier = Modifier.padding(start = 16.dp).clickable { dialog = "kind:${event.name}:${moment.minutes}" },
                    )
                }
                TextButton(
                    enabled = signal.moments.size < SunSignal.MAX,
                    onClick = { dialog = "add:${event.name}" },
                    modifier = Modifier.padding(start = 24.dp),
                ) { Text(stringResource(R.string.sun_add)) }
            }
        }
        if (sun.hasAlarm) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.sun_sound)) },
                supportingContent = { Text(soundName(sun.sound)) },
                modifier = Modifier.clickable { dialog = "sound" },
            )
            SwitchRow(title = stringResource(R.string.sun_vibrate), checked = sun.vibrate) { on -> updateSun { it.copy(vibrate = on) } }
        }
        Text(
            stringResource(R.string.sun_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }

    val current = dialog
    val parts = current?.split(':').orEmpty()
    val event = parts.getOrNull(1)?.let { name -> SunEvent.entries.firstOrNull { it.name == name } }
    when {
        current == "sound" -> SoundPicker(
            initial = sun.sound,
            onDismiss = { dialog = null },
            onPick = { picked ->
                dialog = null
                updateSun { it.copy(sound = picked ?: AlarmSound.Default) }
            },
        )
        parts.firstOrNull() == "kind" && event != null -> {
            val minutes = parts.getOrNull(2)?.toIntOrNull()
            val moment = sun.of(event).moments.firstOrNull { it.minutes == minutes }
            if (moment == null) {
                // Сигнал убрали, пока окно было открыто.
                LaunchedEffect(current) { dialog = null }
            } else {
                ChoiceDialog(
                    title = momentText(context, event, moment.minutes),
                    choices = SunSignalKind.entries,
                    selected = moment.kind,
                    label = { stringResource(kindTitle(it)) + " — " + stringResource(kindText(it)) },
                    onDismiss = { dialog = null },
                    onSelect = { kind ->
                        updateSignal(event) { it.copy(moments = it.moments.map { m -> if (m.minutes == moment.minutes) m.copy(kind = kind) else m }) }
                    },
                )
            }
        }
        parts.firstOrNull() == "add" && event != null -> SunMomentDialog(
            title = stringResource(if (event == SunEvent.SUNRISE) R.string.sun_sunrise_signal else R.string.sun_sunset_signal),
            onDismiss = { dialog = null },
            onDone = { moment ->
                dialog = null
                // Новый сигнал на то же «за сколько» заменяет прежний.
                updateSignal(event) { it.copy(moments = SunSignal.normalize(listOf(moment) + it.moments)) }
            },
        )
    }
}

/** Новый сигнал: за сколько минут до события — готовое или своё — и чем сигналить. */
@Composable
private fun SunMomentDialog(title: String, onDismiss: () -> Unit, onDone: (SunMoment) -> Unit) {
    val context = LocalContext.current
    var minutes by rememberSaveable { mutableStateOf(SunSignal.DEFAULT_LEAD.toString()) }
    var kind by rememberSaveable { mutableStateOf(SunSignalKind.NOTIFICATION) }
    val value = minutes.toIntOrNull()
    val valid = value != null && value in 0..SunSignal.MAX_MINUTES

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { TitleText(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in SunSignal.CHOICES) {
                        FilterChip(
                            selected = value == choice,
                            onClick = { minutes = choice.toString() },
                            label = {
                                Text(if (choice == 0) stringResource(R.string.sun_at_moment) else stringResource(R.string.warning_before, leadText(context, choice)))
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { text -> minutes = text.filter { it.isDigit() }.take(3) },
                    label = { Text(stringResource(R.string.sun_add_minutes)) },
                    supportingText = { Text(stringResource(R.string.sun_add_hint)) },
                    isError = !valid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                for (choice in SunSignalKind.entries) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = kind == choice, role = Role.RadioButton) { kind = choice },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = kind == choice, onClick = null)
                        Column(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
                            Text(stringResource(kindTitle(choice)))
                            Text(stringResource(kindText(choice)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onDone(SunMoment(value ?: 0, kind)) }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
