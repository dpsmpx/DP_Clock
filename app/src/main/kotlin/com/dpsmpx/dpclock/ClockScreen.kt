package com.dpsmpx.dpclock

import android.content.Intent
import android.icu.text.DateFormat
import android.icu.util.TimeZone
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dpsmpx.dpclock.ui.AppIcons
import kotlinx.coroutines.delay
import java.util.Date
import android.text.format.DateFormat as SystemTimeFormat

/** Диаметр круга — доля меньшей стороны окна. */
private const val CIRCLE_FRACTION = 0.7f

/** Ширина строки времени — доля диаметра: так углы строки остаются внутри круга. */
private const val TEXT_FRACTION = 0.75f

/** Текущее время в круге по центру окна (D-004); внизу — «Ночные часы» (D-017, п. 4). */
@Composable
fun ClockScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val text = currentTimeText()
    Surface(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(min(maxWidth, maxHeight) * CIRCLE_FRACTION),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    BasicText(
                        text = text,
                        modifier = Modifier.fillMaxWidth(TEXT_FRACTION),
                        style = MaterialTheme.typography.displayLarge.copy(
                            color = LocalContentColor.current,
                            textAlign = TextAlign.Center,
                            // Цифры одной ширины: размер текста не скачет от секунды к секунде.
                            fontFeatureSettings = "tnum",
                        ),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(maxFontSize = 400.sp),
                    )
                }
            }
            TextButton(
                onClick = { context.startActivity(Intent(context, NightClockActivity::class.java)) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) {
                Icon(AppIcons.Moon, contentDescription = null)
                Text(stringResource(R.string.night_clock), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * Текущее время строкой ЧЧ:ММ:СС по правилам языка; 12- или 24-часовой
 * формат — как в настройках системы. Время идёт, только пока экран виден.
 */
@Composable
private fun currentTimeText(): String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]

    // Настройку 12/24 меняют вне приложения — перечитать при каждом возврате на экран.
    var is24Hour by remember { mutableStateOf(SystemTimeFormat.is24HourFormat(context)) }
    LifecycleStartEffect(context) {
        is24Hour = SystemTimeFormat.is24HourFormat(context)
        onStopOrDispose {}
    }

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                delay(millisToNextSecond(nowMillis))
            }
        }
    }

    val format = remember(locale, is24Hour) {
        DateFormat.getInstanceForSkeleton(if (is24Hour) "Hms" else "hms", locale)
    }
    // Часовой пояс — системный на момент показа: его меняют, пока приложение открыто.
    format.timeZone = TimeZone.getTimeZone(java.util.TimeZone.getDefault().id)
    return format.format(Date(nowMillis))
}
