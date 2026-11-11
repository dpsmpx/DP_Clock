package com.dpsmpx.dpclock.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.Settings as SystemSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.Alarm
import com.dpsmpx.dpclock.model.AlarmRules
import com.dpsmpx.dpclock.model.AlarmSound
import com.dpsmpx.dpclock.model.Exceptions
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.SignalEvent
import com.dpsmpx.dpclock.model.SignalOutcome
import com.dpsmpx.dpclock.model.SignalSource
import com.dpsmpx.dpclock.persistLater
import com.dpsmpx.dpclock.platform.BatteryCheck
import com.dpsmpx.dpclock.platform.TimeText
import java.time.Instant
import java.time.ZoneId

/** Итог одной проверки. */
enum class CheckStatus { OK, WARNING, PROBLEM }

/** Строка самопроверки: что проверено, что вышло и, если можно, кнопка «исправить». */
class CheckLine(val status: CheckStatus, val title: String, val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

/** Сигнал позже расчётного больше чем на это — «опоздал». */
private const val LATE_MILLIS = 60_000L
private const val JOURNAL_DAYS = 30

/**
 * Самопроверка «прозвонит ли будильник» (D-036, п. 4): ближайший будильник — при
 * ключевых (D-037) ближайший ключевой, — поставлен ли он в Android, разрешения,
 * громкость, сигнал, батарея, оболочка телефона и как сигналы приходили раньше.
 * Ниже — «Если будильник не зазвонил»: частые причины.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmCheckDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = DpClockApp.graph(context).repository
    val alarms by repository.alarms.collectAsStateWithLifecycle(emptyList())
    val exceptions by repository.exceptions.collectAsStateWithLifecycle(Exceptions.NONE)
    val settings by repository.settings.collectAsStateWithLifecycle(Settings())
    val events by repository.events.collectAsStateWithLifecycle(emptyList())
    val reliability = rememberReliability()
    val now = rememberNowMillis(10_000L)
    val lines = alarmCheckLines(context, alarms, exceptions, settings, events, reliability, now)
    val problems = lines.count { it.status == CheckStatus.PROBLEM }
    val warnings = lines.count { it.status == CheckStatus.WARNING }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { TitleText(stringResource(R.string.check_title)) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.back)) } },
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                problems > 0 -> MaterialTheme.colorScheme.errorContainer
                                warnings > 0 -> MaterialTheme.colorScheme.tertiaryContainer
                                else -> MaterialTheme.colorScheme.secondaryContainer
                            },
                        ),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Text(
                            when {
                                problems > 0 -> stringResource(R.string.check_verdict_problems, problems)
                                warnings > 0 -> stringResource(R.string.check_verdict_warnings, warnings)
                                else -> stringResource(R.string.check_verdict_ok)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(lines) { line -> CheckRow(line) }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        stringResource(R.string.help_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
                    )
                }
                items(HELP) { (question, answer) -> HelpRow(stringResource(question), stringResource(answer)) }
            }
        }
    }
}

@Composable
private fun CheckRow(line: CheckLine) {
    val color = when (line.status) {
        CheckStatus.OK -> MaterialTheme.colorScheme.primary
        CheckStatus.WARNING -> MaterialTheme.colorScheme.tertiary
        CheckStatus.PROBLEM -> MaterialTheme.colorScheme.error
    }
    ListItem(
        leadingContent = {
            Icon(
                if (line.status == CheckStatus.OK) AppIcons.Check else AppIcons.Close,
                // Для TalkBack статус — словом, а не цветом.
                contentDescription = stringResource(
                    when (line.status) {
                        CheckStatus.OK -> R.string.check_status_ok
                        CheckStatus.WARNING -> R.string.check_status_warning
                        CheckStatus.PROBLEM -> R.string.check_status_problem
                    },
                ),
                tint = color,
                modifier = Modifier.size(24.dp),
            )
        },
        headlineContent = { Text(line.title) },
        supportingContent = { Text(line.text) },
        trailingContent = line.onAction?.let { act -> { TextButton(onClick = act) { Text(line.action.orEmpty()) } } },
    )
}

@Composable
private fun HelpRow(question: String, answer: String) {
    var open by rememberSaveable(question) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(question, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(if (open) AppIcons.Up else AppIcons.ExpandMore, contentDescription = null, tint = Color.Unspecified)
        }
        if (open) Text(answer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

/** «Если будильник не зазвонил»: вопрос и ответ. */
private val HELP = listOf(
    R.string.help_q_killed to R.string.help_a_killed,
    R.string.help_q_silent to R.string.help_a_silent,
    R.string.help_q_late to R.string.help_a_late,
    R.string.help_q_reboot to R.string.help_a_reboot,
    R.string.help_q_skipped to R.string.help_a_skipped,
    R.string.help_q_report to R.string.help_a_report,
)

/** Все проверки по порядку важности. */
private fun alarmCheckLines(
    context: Context,
    alarms: List<Alarm>,
    exceptions: Exceptions,
    settings: Settings,
    events: List<SignalEvent>,
    reliability: Reliability,
    now: Long,
): List<CheckLine> = buildList {
    val zone = ZoneId.systemDefault()
    val nowInstant = Instant.ofEpochMilli(now)
    fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    val fix = text(R.string.fix)

    // 1. Какой будильник ждём: при ключевых — ближайший ключевой (D-037).
    val wake = AlarmRules.nearestAlarm(AlarmRules.forSleep(alarms), nowInstant, zone, exceptions)
    if (wake == null) {
        add(CheckLine(CheckStatus.WARNING, text(R.string.check_alarm), text(R.string.no_enabled_alarms)))
    } else {
        val (alarm, at) = wake
        val name = alarm.label.ifBlank { text(R.string.ring_alarm_title) }
        add(CheckLine(CheckStatus.OK, text(if (alarm.key) R.string.check_alarm_key else R.string.check_alarm), name + " — " + TimeText.fullDateTime(context, at.toEpochMilli())))
        // 2. Чем он разбудит.
        when {
            alarm.isUnnoticeable -> add(CheckLine(CheckStatus.PROBLEM, text(R.string.check_signal), text(R.string.check_signal_none)))
            alarm.sound == AlarmSound.Silent -> add(CheckLine(CheckStatus.WARNING, text(R.string.check_signal), text(R.string.check_signal_vibration)))
            else -> add(CheckLine(CheckStatus.OK, text(R.string.check_signal), text(if (alarm.vibrate) R.string.check_signal_sound_vibration else R.string.check_signal_sound)))
        }
    }

    // 3. Android знает о ближайшем будильнике: после принудительной остановки приложения — нет.
    val expected = AlarmRules.nearest(alarms, nowInstant, zone, exceptions)
    if (expected != null && reliability.exactAlarms) {
        val system = context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
        val set = system != null && system <= expected.toEpochMilli() + LATE_MILLIS
        add(
            if (set) CheckLine(CheckStatus.OK, text(R.string.check_scheduled), text(R.string.check_scheduled_ok))
            else CheckLine(CheckStatus.PROBLEM, text(R.string.check_scheduled), text(R.string.check_scheduled_missing), fix) {
                persistLater { DpClockApp.graph(context).repository.rescheduleAll() }
            },
        )
    }

    // 4. Разрешения.
    for ((issue, ok) in listOf(Issue.NOTIFICATIONS to reliability.notifications, Issue.FULL_SCREEN to reliability.fullScreen, Issue.EXACT_ALARMS to reliability.exactAlarms)) {
        add(
            if (ok) CheckLine(CheckStatus.OK, text(checkName(issue)), text(R.string.check_allowed))
            else CheckLine(CheckStatus.PROBLEM, text(issue.title), text(issue.text), fix) { context.openFix(issue) },
        )
    }

    // 5. Громкость и «Не беспокоить».
    add(
        when {
            reliability.volumeOff -> CheckLine(CheckStatus.PROBLEM, text(Issue.VOLUME_OFF.title), text(Issue.VOLUME_OFF.text), fix) { context.openFix(Issue.VOLUME_OFF) }
            reliability.volumeLow -> CheckLine(CheckStatus.WARNING, text(Issue.VOLUME_LOW.title), text(Issue.VOLUME_LOW.text), fix) { context.openFix(Issue.VOLUME_LOW) }
            else -> CheckLine(CheckStatus.OK, text(R.string.alarm_volume), text(R.string.volume_value, reliability.alarmVolume, reliability.alarmVolumeMax))
        },
    )
    if (reliability.dndBlocksAlarms) add(CheckLine(CheckStatus.PROBLEM, text(Issue.DND.title), text(Issue.DND.text), fix) { context.openFix(Issue.DND) })

    // 6. Батарея: заряд и экономия.
    val percent = reliability.batteryPercent
    if (percent != null) {
        add(
            if (BatteryCheck.isRisky(percent, reliability.charging)) CheckLine(CheckStatus.WARNING, text(R.string.check_battery), text(R.string.check_battery_low, percent))
            else CheckLine(CheckStatus.OK, text(R.string.check_battery), text(if (reliability.charging) R.string.check_battery_charging else R.string.check_battery_ok, percent)),
        )
    }
    // 7. Оболочка телефона: автозапуск и ограничения батареи.
    if (reliability.needsVendorSetup) {
        val done = settings.xiaomiHintsDone || reliability.batteryUnrestricted
        add(
            CheckLine(
                if (done) CheckStatus.OK else CheckStatus.WARNING,
                text(R.string.check_vendor, reliability.vendor.brands),
                if (settings.xiaomiHintsDone) text(R.string.check_vendor_done) else text(vendorHints(reliability.vendor)),
                text(R.string.check_open),
            ) { context.openFix(Issue.XIAOMI) },
        )
    } else if (!reliability.batteryUnrestricted) {
        add(CheckLine(CheckStatus.OK, text(R.string.check_battery_saving), text(R.string.vendor_other_hints), text(R.string.check_open)) { context.openBatterySettings() })
    }

    // 8. Как сигналы приходили раньше.
    val since = now - JOURNAL_DAYS * 24 * 3_600_000L
    val recent = events.filter { it.source == SignalSource.ALARM && it.scheduledAt >= since }
    if (recent.isNotEmpty()) {
        val missed = recent.count { it.outcome == SignalOutcome.MISSED }
        val late = recent.count { (it.lateMillis ?: 0L) > LATE_MILLIS }
        add(
            if (missed == 0 && late == 0) CheckLine(CheckStatus.OK, text(R.string.check_journal), text(R.string.check_journal_ok, recent.size))
            else CheckLine(CheckStatus.WARNING, text(R.string.check_journal), text(R.string.check_journal_bad, recent.size, late, missed)),
        )
    }
}

/** Нейтральное название разрешения — для строки «в порядке». */
private fun checkName(issue: Issue): Int = when (issue) {
    Issue.NOTIFICATIONS -> R.string.onboarding_item_notifications
    Issue.FULL_SCREEN -> R.string.onboarding_item_full_screen
    else -> R.string.onboarding_item_exact
}

/** Системный список «Экономия заряда»: исключить приложение можно там. */
private fun Context.openBatterySettings() {
    runCatching { startActivity(Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        .onFailure { openFix(Issue.XIAOMI) }
}
