package com.dpsmpx.dpclock.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.media.AudioManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.net.toUri
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.Countdown
import com.dpsmpx.dpclock.model.Moment
import com.dpsmpx.dpclock.platform.Clocks
import kotlinx.coroutines.delay

/** «Сейчас», обновляемое с периодом [periodMillis], пока экран виден. */
@Composable
fun rememberNowMillis(periodMillis: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, periodMillis) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(periodMillis - now % periodMillis)
            }
        }
    }
    return now
}

/** Момент для таймеров и секундомера, обновляемый с периодом [periodMillis]. */
@Composable
fun rememberMoment(periodMillis: Long, active: Boolean = true): Moment {
    var now by remember { mutableStateOf(Clocks.moment()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, periodMillis, active) {
        now = Clocks.moment()
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = Clocks.moment()
                delay(periodMillis)
            }
        }
    }
    return now
}

/** «через 7 ч 20 мин». */
fun untilText(resources: Resources, millis: Long): String {
    val c = Countdown.of(millis)
    return when {
        c.days > 0 -> resources.getString(R.string.in_days_hours_minutes, c.days, c.hours, c.minutes)
        c.hours > 0 -> resources.getString(R.string.in_hours_minutes, c.hours, c.minutes)
        else -> resources.getString(R.string.in_minutes, c.minutes)
    }
}

/** Проблема надёжности: что не так и где это исправить (SCREEN_MAP.md, п. 1, 10). */
enum class Issue(val title: Int, val text: Int) {
    NOTIFICATIONS(R.string.issue_notifications, R.string.issue_notifications_text),
    FULL_SCREEN(R.string.issue_full_screen, R.string.issue_full_screen_text),
    EXACT_ALARMS(R.string.issue_exact, R.string.issue_exact_text),
    VOLUME_OFF(R.string.issue_volume_off, R.string.issue_volume_off_text),
    VOLUME_LOW(R.string.issue_volume_low, R.string.issue_volume_low_text),
    DND(R.string.issue_dnd, R.string.issue_dnd_text),
    XIAOMI(R.string.issue_xiaomi, R.string.issue_xiaomi_text),
}

/** Состояние разрешений; перечитывается при каждом возврате на экран. */
data class Reliability(
    val notifications: Boolean,
    val fullScreen: Boolean,
    val exactAlarms: Boolean,
    val isXiaomi: Boolean,
    /** Громкость потока будильника и её предел. */
    val alarmVolume: Int,
    val alarmVolumeMax: Int,
    /** «Не беспокоить» сейчас не пропускает будильники. */
    val dndBlocksAlarms: Boolean,
) {
    val volumeOff: Boolean get() = alarmVolume == 0
    val volumeLow: Boolean get() = !volumeOff && alarmVolume * 5 <= alarmVolumeMax

    fun issues(xiaomiHintsDone: Boolean): List<Issue> = buildList {
        if (!notifications) add(Issue.NOTIFICATIONS)
        if (!fullScreen) add(Issue.FULL_SCREEN)
        if (!exactAlarms) add(Issue.EXACT_ALARMS)
        if (volumeOff) add(Issue.VOLUME_OFF)
        if (volumeLow) add(Issue.VOLUME_LOW)
        if (dndBlocksAlarms) add(Issue.DND)
        if (isXiaomi && !xiaomiHintsDone) add(Issue.XIAOMI)
    }

    companion object {
        fun read(context: Context): Reliability {
            val notifications = context.getSystemService(NotificationManager::class.java)
            val audio = context.getSystemService(AudioManager::class.java)
            return Reliability(
                notifications = notifications.areNotificationsEnabled(),
                fullScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || notifications.canUseFullScreenIntent(),
                exactAlarms = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
                isXiaomi = Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
                    Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco"),
                alarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM),
                alarmVolumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                dndBlocksAlarms = dndBlocksAlarms(notifications),
            )
        }

        private fun dndBlocksAlarms(manager: NotificationManager): Boolean = runCatching {
            when (manager.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_NONE -> true
                NotificationManager.INTERRUPTION_FILTER_PRIORITY ->
                    manager.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS == 0
                else -> false
            }
        }.getOrDefault(false)
    }
}

/** Состояние надёжности; перечитывается при возврате на экран и раз в 2 с, пока экран открыт: громкость меняют и кнопками. */
@Composable
fun rememberReliability(): Reliability {
    val context = LocalContext.current
    var state by remember { mutableStateOf(Reliability.read(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LifecycleResumeEffect(context) {
        state = Reliability.read(context)
        onPauseOrDispose {}
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(2_000L)
                state = Reliability.read(context)
            }
        }
    }
    return state
}

/** Системная настройка, где исправляется [issue]. */
fun Context.openFix(issue: Issue) {
    val pkg = "package:$packageName".toUri()
    val intent = when (issue) {
        Issue.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        Issue.FULL_SCREEN ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            }
        Issue.EXACT_ALARMS -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
        Issue.VOLUME_OFF, Issue.VOLUME_LOW -> {
            if (raiseAlarmVolume()) return
            Intent(Settings.ACTION_SOUND_SETTINGS)
        }
        Issue.DND -> Intent(Settings.ACTION_SOUND_SETTINGS)
        Issue.XIAOMI -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    }
    runCatching { startActivity(intent) }
        .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
}

/**
 * Громкость будильника — до 70 % предела, с системным ползунком на экране: его
 * можно сразу поправить. false — система не дала (тогда открываются настройки звука).
 */
private fun Context.raiseAlarmVolume(): Boolean = runCatching {
    val audio = getSystemService(AudioManager::class.java)
    val target = maxOf(audio.getStreamVolume(AudioManager.STREAM_ALARM), (audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 7 + 9) / 10)
    audio.setStreamVolume(AudioManager.STREAM_ALARM, target, AudioManager.FLAG_SHOW_UI)
    true
}.getOrDefault(false)

/**
 * Разрешение на уведомления — при создании первого будильника или таймера,
 * с пояснением, что без него сигнала не будет (SCREEN_MAP.md, п. 10).
 * Возвращает действие «проверить и при необходимости спросить».
 */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    var explain by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text(stringResource(R.string.permission_notifications_title)) },
            text = { Text(stringResource(R.string.permission_notifications_text)) },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) { Text(stringResource(R.string.allow)) }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text(stringResource(R.string.not_now)) } },
        )
    }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            explain = true
        }
    }
}
