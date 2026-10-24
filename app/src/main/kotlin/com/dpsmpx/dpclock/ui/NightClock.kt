package com.dpsmpx.dpclock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.AlarmRules
import com.dpsmpx.dpclock.model.Exceptions
import com.dpsmpx.dpclock.platform.TimeText
import java.time.Instant
import java.time.ZoneId

/** Тёмно-красный на чёрном: виден ночью и почти не светит в комнату. */
private val NightText = Color(0xFF9A2B2B)
private val NightDim = Color(0xFF5E1E1E)

/**
 * Ночные часы (D-007, п. 5; D-017, п. 4): крупное время и ближайший будильник
 * на чёрном. Раз в минуту всё сдвигается на несколько точек — экран OLED не выгорает.
 */
@Composable
fun NightClock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = DpClockApp.graph(context).repository
    val alarms by repository.alarms.collectAsStateWithLifecycle(emptyList())
    val exceptions by repository.exceptions.collectAsStateWithLifecycle(Exceptions.NONE)
    val now = rememberNowMillis(1_000L)
    val nearest = AlarmRules.nearest(alarms, Instant.ofEpochMilli(now), ZoneId.systemDefault(), exceptions)
    val minute = now / 60_000L
    Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            Modifier.offset(x = (minute * 37 % 41 - 20).toInt().dp, y = (minute * 53 % 61 - 30).toInt().dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                TimeText.time(context, now),
                color = NightText,
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 104.sp, fontFeatureSettings = "tnum"),
            )
            if (nearest != null) {
                Text(
                    stringResource(R.string.night_next_alarm, TimeText.time(context, nearest.toEpochMilli())),
                    color = NightDim,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}
