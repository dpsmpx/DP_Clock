package com.dpsmpx.dpclock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dpsmpx.dpclock.R

private const val STEPS = 3

/**
 * Первый запуск (D-029, п. 10): три шага вместо одной плашки — уведомления; сигнал
 * поверх экрана и точное время; на Xiaomi — автозапуск и батарея, на других —
 * громкость и «Не беспокоить». Каждый шаг — с состоянием и «Исправить»; можно пропустить.
 */
@Composable
fun OnboardingDialog(onXiaomiDone: () -> Unit, onDone: () -> Unit) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        OnboardingContent(onXiaomiDone, onDone)
    }
}

@Composable
fun OnboardingContent(onXiaomiDone: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val reliability = rememberReliability()
    val requestNotifications = rememberNotificationPermissionRequest()
    var step by rememberSaveable { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(R.string.onboarding_step, step + 1, STEPS),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    0 -> {
                        Title(R.string.onboarding_notifications_title)
                        Body(R.string.onboarding_notifications_text)
                        if (reliability.notifications) {
                            Status(Issue.NOTIFICATIONS, ok = true)
                        } else {
                            Button(onClick = requestNotifications) { Text(stringResource(R.string.onboarding_allow)) }
                        }
                    }
                    1 -> {
                        Title(R.string.onboarding_signal_title)
                        Body(R.string.onboarding_signal_text)
                        Status(Issue.FULL_SCREEN, ok = reliability.fullScreen)
                        Status(Issue.EXACT_ALARMS, ok = reliability.exactAlarms)
                    }
                    else -> if (reliability.needsVendorSetup) {
                        Text(reliability.vendor.brands, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                        Body(vendorHints(reliability.vendor))
                        OutlinedButton(onClick = { context.openAppSettings() }) { Text(stringResource(R.string.open_app_settings)) }
                        TextButton(onClick = onXiaomiDone) { Text(stringResource(R.string.xiaomi_done)) }
                    } else {
                        Title(R.string.onboarding_volume_title)
                        Body(R.string.onboarding_volume_text)
                        Status(if (reliability.volumeOff) Issue.VOLUME_OFF else Issue.VOLUME_LOW, ok = !reliability.volumeOff && !reliability.volumeLow)
                        Status(Issue.DND, ok = !reliability.dndBlocksAlarms)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDone) { Text(stringResource(R.string.onboarding_skip)) }
                Spacer(Modifier.weight(1f))
                if (step > 0) TextButton(onClick = { step-- }) { Text(stringResource(R.string.back)) }
                Button(onClick = { if (step < STEPS - 1) step++ else onDone() }) {
                    Text(stringResource(if (step < STEPS - 1) R.string.onboarding_next else R.string.done))
                }
            }
        }
    }
}

@Composable
private fun Title(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Body(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
}

/** Нейтральное название пункта: у проблем названия — «что не так», для «в порядке» не годятся. */
private fun itemName(issue: Issue): Int = when (issue) {
    Issue.NOTIFICATIONS -> R.string.onboarding_item_notifications
    Issue.FULL_SCREEN -> R.string.onboarding_item_full_screen
    Issue.EXACT_ALARMS -> R.string.onboarding_item_exact
    Issue.VOLUME_OFF, Issue.VOLUME_LOW -> R.string.onboarding_volume_title
    Issue.DND -> R.string.onboarding_item_dnd
    Issue.XIAOMI -> R.string.vendor_setup
}

/** Состояние разрешения: «в порядке» или что не так и «Исправить». */
@Composable
private fun Status(issue: Issue, ok: Boolean) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(stringResource(itemName(issue))) },
        supportingContent = { Text(stringResource(if (ok) R.string.onboarding_ok else issue.text)) },
        leadingContent = {
            Icon(
                if (ok) AppIcons.Check else AppIcons.Close,
                contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
        trailingContent = if (ok) null else ({ TextButton(onClick = { context.openFix(issue) }) { Text(stringResource(R.string.fix)) } }),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
