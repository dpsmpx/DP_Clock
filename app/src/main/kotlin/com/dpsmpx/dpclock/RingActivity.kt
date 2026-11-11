package com.dpsmpx.dpclock

import android.app.KeyguardManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.SleepRules
import com.dpsmpx.dpclock.model.VolumeButtons
import com.dpsmpx.dpclock.platform.RingItem
import com.dpsmpx.dpclock.platform.RingService
import com.dpsmpx.dpclock.platform.Ringing
import com.dpsmpx.dpclock.platform.SunRing
import com.dpsmpx.dpclock.platform.TimeText
import com.dpsmpx.dpclock.ui.FitText
import com.dpsmpx.dpclock.ui.WakeChallenge
import com.dpsmpx.dpclock.ui.rememberNowMillis
import com.dpsmpx.dpclock.ui.sunTitle
import kotlinx.coroutines.flow.first

/**
 * Экран сигнала поверх блокировки (SCREEN_MAP.md, п. 3, 6). Работает и до
 * первой разблокировки после перезагрузки. Кнопки крупные: нажимаются с
 * первого раза спросонья; телефоном боком — справа (D-022, п. 6). С подтверждением пробуждения «Выключить» открывает
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
        // Пока звонит или светает — экран не гаснет.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                // Яркость рассвета — только у мягкого пробуждения; сигнал — с яркостью телефона.
                val sunrise = (item as? RingItem.GentleRing)?.let { SleepRules.sunrise(gentleProgress(it, rememberNowMillis(1_000L))) }
                SideEffect {
                    window.attributes = window.attributes.apply {
                        screenBrightness = sunrise?.brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
                if (item is RingItem.GentleRing && sunrise != null) {
                    SunriseContent(item, Color(sunrise.red, sunrise.green, sunrise.blue), onAction = ::onAction)
                    return@DpClockTheme
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        if (item is RingItem.AlarmRing && checking.value == item.key) {
                            WakeChallenge(
                                check = item.wakeCheck,
                                onInteract = { send(RingService.ACTION_QUIET, item.alarmId) },
                                onPassed = {
                                    checking.value = null
                                    dismiss(item)
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
        if (action == RingService.ACTION_DISMISS && item is RingItem.AlarmRing) {
            dismiss(item)
            return
        }
        val id = when (item) {
            is RingItem.AlarmRing -> item.alarmId
            is RingItem.TimerRing -> item.runId
            is RingItem.RecheckRing -> item.alarmId
            is RingItem.GentleRing -> item.alarmId
            is SunRing -> item.event.name
        }
        send(action, id)
    }

    /** Выключить будильник и открыть приложение после него (D-017, п. 5) — после разблокировки. */
    private fun dismiss(item: RingItem.AlarmRing) {
        startService(RingService.intent(this, RingService.ACTION_DISMISS, item.alarmId).putExtra(RingService.EXTRA_FROM_SCREEN, true))
        val launch = item.afterApp.takeIf { it.isNotEmpty() }?.let(packageManager::getLaunchIntentForPackage) ?: return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = startActivity(launch)
                },
            )
        } else {
            startActivity(launch)
        }
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
                    is RingItem.TimerRing -> onAction(if (item.waiting) RingService.ACTION_TIMER_NEXT else RingService.ACTION_TIMER_ADD_MINUTE, item)
                    // У будильника восхода и заката «Отложить» нет.
                    is RingItem.RecheckRing, is RingItem.GentleRing, is SunRing -> Unit
                }
                VolumeButtons.DISMISS -> when (item) {
                    is RingItem.AlarmRing -> onAction(RingService.ACTION_DISMISS, item)
                    is RingItem.TimerRing -> onAction(RingService.ACTION_TIMER_STOP, item)
                    is SunRing -> onAction(RingService.ACTION_SUN_STOP, item)
                    is RingItem.RecheckRing, is RingItem.GentleRing -> Unit
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
    val title = when (item) {
        is RingItem.AlarmRing ->
            item.label.ifBlank { stringResource(R.string.ring_alarm_title) } + " · " + TimeText.time(context, item.hour, item.minute)
        is RingItem.TimerRing -> stringResource(
            if (item.finished) R.string.ring_timer_named_finished else R.string.ring_step_named_finished,
            item.name.ifBlank { stringResource(R.string.timer_default_name) },
        )
        is RingItem.RecheckRing -> stringResource(R.string.recheck_title)
        is RingItem.GentleRing -> stringResource(R.string.gentle_title)
        is SunRing -> sunTitle(context, item.event, item.minutes) + " · " + TimeText.time(context, item.eventAt)
    }
    val info = @Composable {
        FitText(TimeText.time(context, now), style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        // TalkBack (D-022, п. 3): экран объявляет, что звонит; сменился сигнал — объявит новый.
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
        )
        if (item is RingItem.RecheckRing) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.recheck_hint), textAlign = TextAlign.Center)
        }
    }
    // Кнопки не ниже 88 dp и растут с крупным шрифтом (D-022, п. 4).
    val buttonModifier = Modifier.fillMaxWidth().heightIn(min = 88.dp)
    val buttons = @Composable {
        when (item) {
            is RingItem.AlarmRing -> {
                if (item.wakeCheck.snoozeAllowed) {
                    FilledTonalButton(onClick = { onAction(RingService.ACTION_SNOOZE, item) }, modifier = buttonModifier) {
                        Text(stringResource(R.string.snooze_for, item.snoozeMinutes), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    }
                }
                Button(onClick = { onAction(RingService.ACTION_DISMISS, item) }, modifier = buttonModifier) {
                    Text(
                        stringResource(if (item.wakeCheck.required) R.string.wake_dismiss_with_check else R.string.dismiss),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            is RingItem.TimerRing -> {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                    // Таймер ждёт «Дальше» (D-025, п. 1): вместо «+1 мин» — следующий шаг.
                    FilledTonalButton(
                        onClick = { onAction(if (item.waiting) RingService.ACTION_TIMER_NEXT else RingService.ACTION_TIMER_ADD_MINUTE, item) },
                        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 88.dp),
                    ) {
                        Text(
                            stringResource(if (item.waiting) R.string.timer_next else R.string.add_minute),
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Button(
                        onClick = { onAction(RingService.ACTION_TIMER_STOP, item) },
                        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 88.dp),
                    ) { Text(stringResource(R.string.stop), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center) }
                }
            }
            is RingItem.RecheckRing -> Button(onClick = { onAction(RingService.ACTION_RECHECK_OK, item) }, modifier = buttonModifier) {
                Text(stringResource(R.string.recheck_awake), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            }
            is SunRing -> Button(onClick = { onAction(RingService.ACTION_SUN_STOP, item) }, modifier = buttonModifier) {
                Text(stringResource(R.string.dismiss), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            }
            // Рассвет рисует SunriseContent; сюда он не попадает.
            is RingItem.GentleRing -> Unit
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp).semantics { paneTitle = title }) {
        if (maxWidth > maxHeight) {
            // Телефон боком (D-022, п. 6): время слева, кнопки справа — под большим пальцем.
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { info() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { buttons() }
            }
        } else {
            // Крупным шрифтом всё не влезает — экран прокручивается; кнопки остаются внизу.
            Column(
                Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(48.dp))
                    info()
                }
                Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 24.dp)) {
                    buttons()
                    Spacer(Modifier.size(24.dp))
                }
            }
        }
    }
}

/** Доля пути рассвета: от начала мягкого пробуждения до будильника. */
private fun gentleProgress(item: RingItem.GentleRing, now: Long): Double =
    ((now - item.startsAt).toDouble() / (item.alarmAt - item.startsAt).coerceAtLeast(1L)).coerceIn(0.0, 1.0)

/** Мягкое пробуждение (D-017, п. 3): экран — цвет рассвета, время и тихие кнопки. */
@Composable
private fun SunriseContent(item: RingItem.GentleRing, color: Color, onAction: (String, RingItem) -> Unit) {
    val context = LocalContext.current
    val now = rememberNowMillis(1_000L)
    // На светлом рассвете — тёмный текст, на тёмном — светлый.
    val text = if (color.red + color.green > 1.1f) Color(0xFF3A1A0A) else Color(0xFFFFD9B0)
    val title = stringResource(R.string.gentle_title)
    BoxWithConstraints(Modifier.fillMaxSize().background(color).safeDrawingPadding().padding(24.dp).semantics { paneTitle = title }) {
        val side = maxWidth > maxHeight
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(if (side) 8.dp else 48.dp))
                FitText(TimeText.time(context, now), style = MaterialTheme.typography.displayLarge, color = text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.gentle_text, item.label.ifBlank { stringResource(R.string.ring_alarm_title) }, TimeText.time(context, item.alarmAt)),
                    style = MaterialTheme.typography.titleMedium,
                    color = text,
                    textAlign = TextAlign.Center,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                if (item.canDismiss) {
                    OutlinedButton(onClick = { onAction(RingService.ACTION_GENTLE_AWAKE, item) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.gentle_awake), color = text, textAlign = TextAlign.Center)
                    }
                }
                OutlinedButton(onClick = { onAction(RingService.ACTION_GENTLE_CLOSE, item) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.gentle_close), color = text, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.size(if (side) 0.dp else 24.dp))
            }
        }
    }
}
