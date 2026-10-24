package com.dpsmpx.dpclock

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.VolumeButtons
import com.dpsmpx.dpclock.platform.RingItem
import com.dpsmpx.dpclock.platform.RingService
import com.dpsmpx.dpclock.platform.Ringing
import com.dpsmpx.dpclock.platform.TimeText
import com.dpsmpx.dpclock.ui.rememberNowMillis
import kotlinx.coroutines.flow.first

/**
 * Экран сигнала поверх блокировки (SCREEN_MAP.md, п. 3, 6). Работает и до
 * первой разблокировки после перезагрузки. Кнопки крупные: нажимаются с
 * первого раза спросонья.
 */
class RingActivity : ComponentActivity() {
    /** Действие кнопок громкости из настроек (D-011, п. 1). */
    private var volumeButtons = VolumeButtons.SNOOZE

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val repository = DpClockApp.graph(this).repository
        setContent {
            DpClockTheme {
                val items by Ringing.items.collectAsStateWithLifecycle()
                val settings by repository.settings.collectAsStateWithLifecycle(Settings())
                SideEffect { volumeButtons = settings.volumeButtons }
                LaunchedEffect(Unit) {
                    // Сигнал выключили — здесь, в уведомлении или автовыключением.
                    Ringing.items.first { it.isEmpty() }
                    finish()
                }
                items.lastOrNull()?.let { RingContent(it, onAction = ::send) }
            }
        }
    }

    private fun send(action: String, id: String) {
        startService(RingService.intent(this, action, id))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isVolumeKey(keyCode)) return super.onKeyDown(keyCode, event)
        val item = Ringing.items.value.lastOrNull() ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) {
            when (volumeButtons) {
                VolumeButtons.SNOOZE -> when (item) {
                    is RingItem.AlarmRing -> send(RingService.ACTION_SNOOZE, item.alarmId)
                    is RingItem.TimerRing -> send(RingService.ACTION_TIMER_ADD_MINUTE, item.runId)
                }
                VolumeButtons.DISMISS -> when (item) {
                    is RingItem.AlarmRing -> send(RingService.ACTION_DISMISS, item.alarmId)
                    is RingItem.TimerRing -> send(RingService.ACTION_TIMER_STOP, item.runId)
                }
                VolumeButtons.NOTHING -> Unit
            }
        }
        // Кнопки не меняют громкость, пока звонит сигнал: их действие — из настроек.
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (isVolumeKey(keyCode) && Ringing.items.value.isNotEmpty()) true else super.onKeyUp(keyCode, event)

    private fun isVolumeKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
}

@Composable
private fun RingContent(item: RingItem, onAction: (String, String) -> Unit) {
    val context = LocalContext.current
    val now = rememberNowMillis(1_000L)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(48.dp))
                Text(TimeText.time(context, now), style = MaterialTheme.typography.displayLarge)
                Spacer(Modifier.height(16.dp))
                Text(
                    when (item) {
                        is RingItem.AlarmRing ->
                            item.label.ifBlank { stringResource(R.string.ring_alarm_title) } + " · " + TimeText.time(context, item.hour, item.minute)
                        is RingItem.TimerRing -> stringResource(
                            if (item.finished) R.string.ring_timer_named_finished else R.string.ring_step_named_finished,
                            item.name.ifBlank { stringResource(R.string.timer_default_name) },
                        )
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
            }
            val buttonModifier = Modifier.fillMaxWidth().height(88.dp)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (item) {
                    is RingItem.AlarmRing -> {
                        FilledTonalButton(onClick = { onAction(RingService.ACTION_SNOOZE, item.alarmId) }, modifier = buttonModifier) {
                            Text(stringResource(R.string.snooze_for, item.snoozeMinutes), style = MaterialTheme.typography.titleLarge)
                        }
                        Button(onClick = { onAction(RingService.ACTION_DISMISS, item.alarmId) }, modifier = buttonModifier) {
                            Text(stringResource(R.string.dismiss), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    is RingItem.TimerRing -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            FilledTonalButton(
                                onClick = { onAction(RingService.ACTION_TIMER_ADD_MINUTE, item.runId) },
                                modifier = Modifier.weight(1f).height(88.dp),
                            ) { Text(stringResource(R.string.add_minute), style = MaterialTheme.typography.titleLarge) }
                            Button(
                                onClick = { onAction(RingService.ACTION_TIMER_STOP, item.runId) },
                                modifier = Modifier.weight(1f).height(88.dp),
                            ) { Text(stringResource(R.string.stop), style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}
