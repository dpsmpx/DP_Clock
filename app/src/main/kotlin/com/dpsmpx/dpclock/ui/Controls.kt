package com.dpsmpx.dpclock.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.ElapsedUnit
import com.dpsmpx.dpclock.model.TaskHistory
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.format.TextStyle

/**
 * Дни недели флажками в порядке языка — равной ширины в одну строку: помещаются
 * и в окно, и в строку будильника. TalkBack читает день целиком (D-022, п. 3).
 */
@Composable
fun DayChips(selected: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (day in orderedDays(locale)) {
            FilterChip(
                selected = day in selected,
                onClick = { onToggle(day) },
                label = { Text(day.getDisplayName(TextStyle.NARROW_STANDALONE, locale), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                modifier = Modifier.weight(1f).semantics { contentDescription = day.getDisplayName(TextStyle.FULL_STANDALONE, locale) },
            )
        }
    }
}

/** Число кнопками «−» и «+»; null — кнопка недоступна. */
@Composable
fun Stepper(label: String, value: String, onMinus: (() -> Unit)?, onPlus: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(enabled = onMinus != null, onClick = { onMinus?.invoke() }) {
            Icon(AppIcons.Remove, contentDescription = stringResource(R.string.less_of, label))
        }
        Text(value, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 64.dp))
        IconButton(enabled = onPlus != null, onClick = { onPlus?.invoke() }) {
            Icon(AppIcons.Add, contentDescription = stringResource(R.string.more_of, label))
        }
    }
}

/** Выбор из [choices] кнопками «−» и «+». */
@Composable
fun <T> ChoiceStepper(label: String, choices: List<T>, value: T, text: (T) -> String, onChange: (T) -> Unit) {
    val index = choices.indexOf(value).coerceAtLeast(0)
    Stepper(
        label,
        text(value),
        onMinus = if (index > 0) ({ onChange(choices[index - 1]) }) else null,
        onPlus = if (index < choices.lastIndex) ({ onChange(choices[index + 1]) }) else null,
    )
}

/** Строка с переключателем — один элемент для TalkBack. */
@Composable
fun SwitchRow(title: String, checked: Boolean, supporting: String? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

/** «1 д 2 ч 30 мин» — длительность предупреждения без предлога (D-025, п. 5). */
fun leadText(context: Context, minutes: Int): String {
    val resources = context.resources
    val days = minutes / (24 * 60)
    val hours = minutes / 60 % 24
    val mins = minutes % 60
    return listOfNotNull(
        days.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.lead_days, it, it) },
        hours.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.lead_hours, it, it) },
        mins.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.lead_minutes, it, it) },
    ).joinToString(" ")
}

/** «2 д 3 ч», «3 мес 5 д», «1 г 2 мес» — сколько прошло (D-033). */
fun elapsedText(context: Context, millis: Long): String {
    val resources = context.resources
    return TaskHistory.elapsed(millis).joinToString(" ") { (unit, n) ->
        when (unit) {
            ElapsedUnit.YEAR -> resources.getQuantityString(R.plurals.elapsed_years, n, n)
            ElapsedUnit.MONTH -> resources.getQuantityString(R.plurals.elapsed_months, n, n)
            ElapsedUnit.DAY -> resources.getQuantityString(R.plurals.lead_days, n, n)
            ElapsedUnit.HOUR -> resources.getQuantityString(R.plurals.lead_hours, n, n)
            ElapsedUnit.MINUTE -> resources.getQuantityString(R.plurals.lead_minutes, n, n)
        }
    }
}

/** «за 1 д, за 5 мин» или «нет». */
fun warningsText(context: Context, minutes: List<Int>): String =
    if (minutes.isEmpty()) context.getString(R.string.warnings_none)
    else minutes.joinToString(", ") { context.getString(R.string.warning_before, leadText(context, it)) }

/**
 * Вибро-отклик кнопки (D-029, п. 7): короткий щелчок, если он не выключен в настройках.
 * Системная настройка «Отклик на касание» тоже действует.
 */
@Composable
fun rememberHaptic(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    val repository = DpClockApp.graph(LocalContext.current).repository
    val enabled by remember(repository) { repository.settings.map { it.haptics } }.collectAsStateWithLifecycle(true)
    return { if (enabled) haptic.performHapticFeedback(HapticFeedbackType.ContextClick) }
}
