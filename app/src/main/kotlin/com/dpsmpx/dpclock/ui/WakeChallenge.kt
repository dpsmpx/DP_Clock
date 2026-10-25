package com.dpsmpx.dpclock.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.KeypadTask
import com.dpsmpx.dpclock.model.PhotoSignature
import com.dpsmpx.dpclock.model.ShakeCounter
import com.dpsmpx.dpclock.model.WakeCheck
import com.dpsmpx.dpclock.model.WakeMethod
import com.dpsmpx.dpclock.model.WakeTasks
import kotlinx.coroutines.delay

/** Подряд столько похожих кадров — место узнано: один случайный кадр не засчитывается. */
private const val PHOTO_FRAMES = 3

/**
 * Проверка пробуждения (D-015, п. 2–3): пример, код, встряхивание, QR-код или
 * снимок места. [onInteract] — человек что-то делает, сигнал можно приглушить;
 * [onPassed] — проверка пройдена. Камера или датчик не помогают — запасной
 * способ, сложные примеры ([WakeCheck.FALLBACK]).
 */
@Composable
fun WakeChallenge(check: WakeCheck, onInteract: () -> Unit, onPassed: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var active by remember(check) {
        mutableStateOf(
            when {
                !check.isComplete -> WakeCheck.FALLBACK
                (check.method == WakeMethod.QR || check.method == WakeMethod.PHOTO) && !context.hasCamera() -> WakeCheck.FALLBACK
                check.method == WakeMethod.SHAKE && !context.hasAccelerometer() -> WakeCheck.FALLBACK
                else -> check
            },
        )
    }
    val fallback = { active = WakeCheck.FALLBACK }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.wake_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (active !== check) {
            Text(stringResource(R.string.wake_fallback_note), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (active.method) {
                WakeMethod.MATH, WakeMethod.CODE -> KeypadChallenge(active, onInteract, onPassed)
                WakeMethod.SHAKE -> ShakeChallenge(active, onInteract, onPassed)
                WakeMethod.QR, WakeMethod.PHOTO -> CameraChallenge(active, onInteract, onPassed, onError = fallback)
                WakeMethod.NONE -> LaunchedEffect(Unit) { onPassed() }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (active.method == WakeMethod.QR || active.method == WakeMethod.PHOTO || active.method == WakeMethod.SHAKE) {
                OutlinedButton(onClick = fallback) { Text(stringResource(R.string.wake_cannot)) }
            }
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
    }
}

/** Пример или код на своей клавиатуре: крупные кнопки нажимаются спросонья. */
@Composable
private fun KeypadChallenge(check: WakeCheck, onInteract: () -> Unit, onPassed: () -> Unit) {
    fun newTask(): KeypadTask = if (check.method == WakeMethod.CODE) WakeTasks.code(check.level) else WakeTasks.math(check.level)
    var task by remember(check) { mutableStateOf(newTask()) }
    var done by remember(check) { mutableIntStateOf(0) }
    var input by remember(check) { mutableStateOf("") }
    var wrong by remember(check) { mutableStateOf(false) }

    val question = @Composable {
        if (check.tasks > 1) Text(stringResource(R.string.wake_task_of, done + 1, check.tasks))
        Text(
            if (check.method == WakeMethod.CODE) stringResource(R.string.wake_type_code) else stringResource(R.string.wake_solve),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        FitText(task.question, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Text(
            input.ifEmpty { " " },
            style = MaterialTheme.typography.headlineMedium,
            color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        // TalkBack сразу скажет «Неверно» (D-022, п. 3).
        if (wrong) {
            Text(
                stringResource(R.string.wake_wrong),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
    val keypad = @Composable {
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        Column(Modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in keys.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (key in row) {
                        // Не ниже 56 dp, растут с крупным шрифтом (D-022, п. 4).
                        val modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                        val press = {
                            onInteract()
                            wrong = false
                            when (key) {
                                "⌫" -> input = input.dropLast(1)
                                "OK" -> if (input == task.answer) {
                                    done++
                                    input = ""
                                    if (done >= check.tasks) onPassed() else task = newTask()
                                } else {
                                    // Неверно — новая задача: перебором не угадать.
                                    wrong = true
                                    input = ""
                                    task = newTask()
                                }
                                else -> if (input.length < 9) input += key
                            }
                        }
                        when (key) {
                            "OK" -> Button(onClick = press, modifier = modifier) { Text(stringResource(R.string.done)) }
                            "⌫" -> FilledTonalButton(onClick = press, modifier = modifier) {
                                Icon(AppIcons.Backspace, contentDescription = stringResource(R.string.backspace))
                            }
                            else -> FilledTonalButton(onClick = press, modifier = modifier) {
                                Text(key, style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }
        }
    }
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        if (maxWidth > maxHeight) {
            // Телефон боком (D-022, п. 6): задача слева, клавиатура справа.
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { question() }
                Box(Modifier.weight(1f).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) { keypad() }
            }
        } else {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                question()
                keypad()
            }
        }
    }
}

@Composable
private fun ShakeChallenge(check: WakeCheck, onInteract: () -> Unit, onPassed: () -> Unit) {
    val context = LocalContext.current
    var count by remember(check) { mutableIntStateOf(0) }
    val interact by rememberUpdatedState(onInteract)
    val passed by rememberUpdatedState(onPassed)
    DisposableEffect(check) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val counter = ShakeCounter()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (counter.onSample(event.values[0], event.values[1], event.values[2], event.timestamp / 1_000_000L)) {
                    count = counter.count
                    interact()
                    if (counter.count >= check.shakes) passed()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensors.unregisterListener(listener) }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.wake_shake), style = MaterialTheme.typography.bodyLarge)
        Text(
            "${count.coerceAtMost(check.shakes)} / ${check.shakes}",
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        LinearProgressIndicator(progress = { count.toFloat() / check.shakes }, modifier = Modifier.fillMaxWidth().height(12.dp))
    }
}

@Composable
private fun CameraChallenge(check: WakeCheck, onInteract: () -> Unit, onPassed: () -> Unit, onError: () -> Unit) {
    val reference = remember(check) { if (check.method == WakeMethod.PHOTO) PhotoSignature.decode(check.reference) else null }
    var similar by remember(check) { mutableIntStateOf(0) }
    var score by remember(check) { mutableDoubleStateOf(-1.0) }
    var otherCode by remember(check) { mutableStateOf(false) }
    var lastInteract by remember(check) { mutableLongStateOf(0L) }
    // Камера смотрит — человек занят проверкой: сигнал молчит, пока она открыта.
    LaunchedEffect(check) {
        while (true) {
            onInteract()
            delay(10_000L)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(if (check.method == WakeMethod.QR) R.string.wake_scan_code else R.string.wake_scan_place),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        CameraScanner(
            mode = if (check.method == WakeMethod.QR) CameraMode.CODE else CameraMode.PHOTO,
            onResult = { found ->
                when (found) {
                    is CameraResult.Code -> if (found.text == check.reference) onPassed() else otherCode = true
                    is CameraResult.Signature -> {
                        val value = reference?.let { PhotoSignature.similarity(it, found.value) } ?: -1.0
                        score = value
                        similar = if (value >= PhotoSignature.threshold(check.level)) similar + 1 else 0
                        if (similar >= PHOTO_FRAMES) onPassed()
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastInteract > 5_000L) {
                    lastInteract = now
                    onInteract()
                }
            },
            onError = onError,
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
        )
        if (otherCode) Text(stringResource(R.string.wake_other_code), color = MaterialTheme.colorScheme.error)
        if (check.method == WakeMethod.PHOTO) {
            LinearProgressIndicator(
                progress = { (score / PhotoSignature.threshold(check.level)).coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
        }
        Spacer(Modifier.size(4.dp))
    }
}

private fun Context.hasAccelerometer(): Boolean =
    getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
