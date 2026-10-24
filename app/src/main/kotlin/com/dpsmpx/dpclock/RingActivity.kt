package com.dpsmpx.dpclock

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
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
import com.dpsmpx.dpclock.ui.WakeChallenge
import com.dpsmpx.dpclock.ui.rememberNowMillis
import kotlinx.coroutines.flow.first

/**
 * Экран сигнала поверх блокировки (SCREEN_MAP.md, п. 3, 6). Работает и до
 * первой разблокировки после перезагрузки. Кнопки крупные: нажимаются с
 * первого раза спросонья. С подтверждением пробуждения «Выключить» открывает
 * проверку (D-015); «Не спите?» — свой экран.
 */
class RingActivity : ComponentActivity() {
    /** Действие кнопок громкости из настроек (D-011, п. 1). */
    private var volumeButtons = VolumeButtons.SNOOZE

    /** Ключ сигнала, для которого открыта проверка пробуждения; null — экран сигнала. */
    private val checking = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        checking.value = savedInstanceState?.getString(STATE_CHECKING)
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
                val item = items.lastOrNull() ?: return@DpClockTheme
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        if (item is RingItem.AlarmRing && checking.value == item.key) {
                            WakeChallenge(
                                check = item.wakeCheck,
                                onInteract = { send(RingService.ACTION_QUIET, item.alarmId) },
                                onPassed = {
                                    checking.value = null
                                    send(RingService.ACTION_DISMISS, item.alarmId)
                                },
                                onBack = { checking.value = null },
                            )
                        } else {
                            RingContent(item, onAction = ::onAction)
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CHECKING, checking.value)
    }

    /** Кнопка экрана сигнала; «Выключить» с проверкой пробуждения открывает проверку. */
    private fun onAction(action: String, item: RingItem) {
        if (action == RingService.ACTION_DISMISS && item is RingItem.AlarmRing && item.wakeCheck.required) {
            checking.value = item.key
            send(RingService.ACTION_QUIET, item.alarmId)
            return
        }
        val id = when (item) {
            is RingItem.AlarmRing -> item.alarmId
            is RingItem.TimerRing -> item.runId
            is RingItem.RecheckRing -> item.alarmId
        }
        send(action, id)
    }

    private fun send(action: String, id: String) {
        startService(RingService.intent(this, action, id))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isVolumeKey(keyCode)) return super.onKeyDown(keyCode, event)
        val item = Ringing.items.value.lastOrNull() ?: return super.onKeyDown(keyCode, event)
        // Во время проверки и на «Не спите?» кнопки громкости ничего не делают.
        if (event.repeatCount == 0 && checking.value == null) {
            when (volumeButtons) {
                VolumeButtons.SNOOZE -> when (item) {
                    is RingItem.AlarmRing -> if (item.wakeCheck.snoozeAllowed) onAction(RingService.ACTION_SNOOZE, item)
                    is RingItem.TimerRing -> onAction(RingService.ACTION_TIMER_ADD_MINUTE, item)
                    is RingItem.RecheckRing -> Unit
                }
                VolumeButtons.DISMISS -> when (item) {
                    is RingItem.AlarmRing -> onAction(RingService.ACTION_DISMISS, item)
                    is RingItem.TimerRing -> onAction(RingService.ACTION_TIMER_STOP, item)
                    is RingItem.RecheckRing -> Unit
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

    private companion object {
        const val STATE_CHECKING = "checking"
    }
}

@Composable
private fun RingContent(item: RingItem, onAction: (String, RingItem) -> Unit) {
    val context = LocalContext.current
    val now = rememberNowMillis(1_000L)
    Column(
        Modifier.fillMaxSize().padding(24.dp),
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
                    is RingItem.RecheckRing -> stringResource(R.string.recheck_title)
                },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            if (item is RingItem.RecheckRing) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.recheck_hint), textAlign = TextAlign.Center)
            }
        }
        val buttonModifier = Modifier.fillMaxWidth().height(88.dp)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (item) {
                is RingItem.AlarmRing -> {
                    if (item.wakeCheck.snoozeAllowed) {
                        FilledTonalButton(onClick = { onAction(RingService.ACTION_SNOOZE, item) }, modifier = buttonModifier) {
                            Text(stringResource(R.string.snooze_for, item.snoozeMinutes), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Button(onClick = { onAction(RingService.ACTION_DISMISS, item) }, modifier = buttonModifier) {
                        Text(
                            stringResource(if (item.wakeCheck.required) R.string.wake_dismiss_with_check else R.string.dismiss),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                is RingItem.TimerRing -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FilledTonalButton(
                            onClick = { onAction(RingService.ACTION_TIMER_ADD_MINUTE, item) },
                            modifier = Modifier.weight(1f).height(88.dp),
                        ) { Text(stringResource(R.string.add_minute), style = MaterialTheme.typography.titleLarge) }
                        Button(
                            onClick = { onAction(RingService.ACTION_TIMER_STOP, item) },
                            modifier = Modifier.weight(1f).height(88.dp),
                        ) { Text(stringResource(R.string.stop), style = MaterialTheme.typography.titleLarge) }
                    }
                }
                is RingItem.RecheckRing -> Button(onClick = { onAction(RingService.ACTION_RECHECK_OK, item) }, modifier = buttonModifier) {
                    Text(stringResource(R.string.recheck_awake), style = MaterialTheme.typography.titleLarge)
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}
