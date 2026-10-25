package com.dpsmpx.dpclock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.AlarmRules
import com.dpsmpx.dpclock.model.Exceptions
import com.dpsmpx.dpclock.model.NoiseKind
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.persistLater
import com.dpsmpx.dpclock.platform.TimeText
import java.time.Instant
import java.time.ZoneId

/** Сторона стрелочных часов ночью — доля меньшей стороны экрана. */
private const val NIGHT_FACE_FRACTION = 0.62f

/**
 * Ночные часы (D-007, п. 5; D-017, п. 4): время в выбранном стиле (D-021, п. 4)
 * и ближайший будильник тёмно-красным на чёрном. Раз в минуту всё сдвигается на
 * несколько точек — экран OLED не выгорает. [controls] — экран ярче: внизу выбор
 * шума (D-021, п. 5); у заставки его нет.
 */
@Composable
fun NightClock(modifier: Modifier = Modifier, controls: Boolean = false) {
    val context = LocalContext.current
    val repository = DpClockApp.graph(context).repository
    val alarms by repository.alarms.collectAsStateWithLifecycle(emptyList())
    val exceptions by repository.exceptions.collectAsStateWithLifecycle(Exceptions.NONE)
    val settings by repository.settings.collectAsStateWithLifecycle(Settings())
    val now = rememberNowMillis(60_000L)
    val nearest = AlarmRules.nearest(alarms, Instant.ofEpochMilli(now), ZoneId.systemDefault(), exceptions)
    val minute = now / 60_000L
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val side = clockSide(maxWidth, maxHeight, NIGHT_FACE_FRACTION)
        Column(
            Modifier.offset(x = (minute * 37 % 41 - 20).toInt().dp, y = (minute * 53 % 61 - 30).toInt().dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ClockFace(settings.appearance, night = true, size = side)
            if (nearest != null) {
                Text(
                    stringResource(R.string.night_next_alarm, TimeText.time(context, nearest.toEpochMilli())),
                    color = NightFace.dim,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (controls) {
            NoiseChoice(
                settings.appearance.nightNoise,
                onChoose = { kind -> persistLater { repository.updateSettings { it.copy(appearance = it.appearance.copy(nightNoise = kind)) } } },
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp),
            )
        }
    }
}

/** Шум в ночных часах: без шума, белый, розовый, коричневый. */
@Composable
private fun NoiseChoice(selected: NoiseKind, onChoose: (NoiseKind) -> Unit, modifier: Modifier) {
    val colors = FilterChipDefaults.filterChipColors(
        labelColor = NightFace.dim,
        selectedLabelColor = Color.Black,
        selectedContainerColor = NightFace.text,
    )
    Box(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (kind in NoiseKind.entries) {
                FilterChip(
                    selected = kind == selected,
                    onClick = { onChoose(kind) },
                    label = { Text(stringResource(noiseTitle(kind))) },
                    colors = colors,
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = kind == selected, borderColor = NightFace.dim),
                )
            }
        }
    }
}

fun noiseTitle(kind: NoiseKind): Int = when (kind) {
    NoiseKind.NONE -> R.string.noise_none
    NoiseKind.WHITE -> R.string.noise_white
    NoiseKind.PINK -> R.string.noise_pink
    NoiseKind.BROWN -> R.string.noise_brown
}
