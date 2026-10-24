package com.dpsmpx.dpclock.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.QuickRequest
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.AlarmSound
import com.dpsmpx.dpclock.model.DurationInput
import com.dpsmpx.dpclock.model.TimerPreset
import com.dpsmpx.dpclock.model.TimerProgram
import com.dpsmpx.dpclock.model.TimerRun
import com.dpsmpx.dpclock.model.TimerState
import com.dpsmpx.dpclock.model.TimerStep
import com.dpsmpx.dpclock.persistLater
import com.dpsmpx.dpclock.platform.RingService
import com.dpsmpx.dpclock.platform.TimeText
import java.util.Locale
import java.util.UUID

/** Раздел «Таймеры» (SCREEN_MAP.md, п. 4). */
@Composable
fun TimersScreen(requestNotifications: () -> Unit, quick: QuickRequest? = null, onQuickHandled: (Long) -> Unit = {}) {
    val repository = DpClockApp.graph(LocalContext.current).repository
    val runs by repository.timerRuns.collectAsStateWithLifecycle(null)
    val presets by repository.presets.collectAsStateWithLifecycle(emptyList())
    var input by rememberSaveable(stateSaver = DurationInputSaver) { mutableStateOf(DurationInput()) }
    var showInput by rememberSaveable { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<TimerPreset?>(null) }
    var newPreset by rememberSaveable { mutableStateOf(false) }
    var sleepOpen by rememberSaveable { mutableStateOf(false) }
    val resources = LocalResources.current
    val now = rememberMoment(200L, active = runs.orEmpty().any { it.state == TimerState.RUNNING })

    // Ярлык «Таймер засыпания» (D-020).
    LaunchedEffect(quick?.token) {
        val request = quick ?: return@LaunchedEffect
        sleepOpen = true
        onQuickHandled(request.token)
    }

    val list = runs ?: return
    val inputVisible = list.isEmpty() || showInput

    fun start(program: TimerProgram, name: String, presetId: String?) {
        requestNotifications()
        showInput = false
        persistLater { repository.startTimer(program, name, presetId) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(list, key = { it.id }) { run -> TimerCard(run, run.remainingAt(now)) }
        if (inputVisible) {
            item(key = "input") {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    DurationEntry(input, onChange = { input = it })
                    Spacer(Modifier.size(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (list.isNotEmpty()) {
                            OutlinedButton(onClick = { showInput = false }) { Text(stringResource(R.string.cancel)) }
                        }
                        OutlinedButton(enabled = !input.isEmpty, onClick = { newPreset = true }) {
                            Text(stringResource(R.string.save_as_preset))
                        }
                        Button(enabled = !input.isEmpty, onClick = {
                            start(TimerProgram.single(input.millis), "", null)
                            input = DurationInput()
                        }) {
                            Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.start))
                        }
                    }
                }
            }
        } else {
            item(key = "new") {
                FilledTonalButton(onClick = { showInput = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.new_timer))
                }
            }
        }
        item(key = "sleep") {
            OutlinedButton(onClick = { sleepOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcons.Moon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.sleep_timer))
            }
        }
        item(key = "presets-title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.presets), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { newPreset = true }) { Text(stringResource(R.string.new_preset)) }
            }
        }
        if (presets.isEmpty()) {
            item(key = "presets-empty") {
                Text(
                    stringResource(R.string.presets_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(presets, key = { "preset:" + it.id }) { preset ->
            PresetRow(
                preset = preset,
                onStart = { start(preset.program, preset.name, preset.id) },
                onEdit = { editingPreset = preset },
                onDelete = { persistLater { repository.deletePreset(preset.id) } },
            )
        }
    }

    if (sleepOpen) {
        SleepTimerDialog(
            onDismiss = { sleepOpen = false },
            onStart = { millis ->
                sleepOpen = false
                start(TimerProgram.sleep(millis), resources.getString(R.string.sleep_timer), null)
            },
        )
    }
    if (newPreset) {
        PresetEditor(
            initial = null,
            initialProgram = if (input.isEmpty) null else TimerProgram.single(input.millis),
            onDismiss = { newPreset = false },
            onSave = {
                newPreset = false
                persistLater { repository.savePreset(it) }
            },
        )
    }
    editingPreset?.let { preset ->
        PresetEditor(
            initial = preset,
            initialProgram = preset.program,
            onDismiss = { editingPreset = null },
            onSave = {
                editingPreset = null
                persistLater { repository.savePreset(it) }
            },
        )
    }
}

@Composable
private fun TimerCard(run: TimerRun, remaining: Long) {
    val context = LocalContext.current
    val repository = DpClockApp.graph(context).repository
    // Звонящий таймер выключается через службу сигнала: она же глушит звук.
    val ringing = run.state == TimerState.RINGING
    fun ringAction(action: String) = context.startService(RingService.intent(context, action, run.id))
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (run.state == TimerState.RINGING) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    run.name.ifBlank { stringResource(R.string.timer_default_name) },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    if (ringing) ringAction(RingService.ACTION_TIMER_STOP) else persistLater { repository.deleteTimer(run.id) }
                }) {
                    Icon(AppIcons.Close, contentDescription = stringResource(R.string.delete))
                }
            }
            if (run.program.sleep) {
                Text(stringResource(R.string.sleep_timer_note), style = MaterialTheme.typography.bodyMedium)
            }
            if (run.program.isChain) {
                val step = run.step.name.ifBlank { stringResource(R.string.step_of, run.stepIndex + 1, run.program.steps.size) }
                val cycle = if (run.program.cycles > 1) " · " + stringResource(R.string.cycle_of, run.cycleIndex + 1, run.program.cycles) else ""
                Text(step + cycle, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                if (run.state == TimerState.RINGING) stringResource(R.string.ring_timer_finished) else TimeText.countdown(remaining),
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                when (run.state) {
                    TimerState.RUNNING -> FilledIconButton(onClick = { persistLater { repository.pauseTimer(run.id) } }) {
                        Icon(AppIcons.Pause, contentDescription = stringResource(R.string.pause))
                    }
                    TimerState.PAUSED -> FilledIconButton(onClick = { persistLater { repository.resumeTimer(run.id) } }) {
                        Icon(AppIcons.Play, contentDescription = stringResource(R.string.resume))
                    }
                    TimerState.RINGING -> Unit
                }
                if (run.state != TimerState.RINGING) {
                    FilledTonalIconButton(onClick = { persistLater { repository.resetTimer(run.id) } }) {
                        Icon(AppIcons.Reset, contentDescription = stringResource(R.string.reset))
                    }
                }
                FilledTonalButton(onClick = {
                    if (ringing) ringAction(RingService.ACTION_TIMER_ADD_MINUTE) else persistLater { repository.addMinute(run.id) }
                }) {
                    Text(stringResource(R.string.add_minute))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetRow(preset: TimerPreset, onStart: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onStart, onLongClick = { menu = true }),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(preset.name, style = MaterialTheme.typography.titleMedium)
                    Text(programSummary(preset.program), style = MaterialTheme.typography.bodyMedium)
                }
                Icon(AppIcons.Play, contentDescription = stringResource(R.string.start))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = {
                menu = false
                onEdit()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                menu = false
                onDelete()
            })
        }
    }
}

@Composable
private fun programSummary(program: TimerProgram): String {
    val total = TimeText.duration(program.totalMillis)
    if (!program.isChain) return total
    val steps = pluralStringResource(R.plurals.steps_count, program.steps.size, program.steps.size)
    val cycles = if (program.cycles > 1) " × " + program.cycles else ""
    return "$total · $steps$cycles"
}

/** Ввод длительности: ЧЧ:ММ:СС, клавиатура 0–9 и быстрые кнопки (D-010, п. 4). */
@Composable
fun DurationEntry(input: DurationInput, onChange: (DurationInput) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 360.dp)) {
        Text(
            String.format(Locale.ROOT, "%02d:%02d:%02d", input.hours, input.minutes, input.seconds),
            style = MaterialTheme.typography.displayMedium,
            color = if (input.isEmpty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (minutes in listOf(1, 5, 10)) {
                OutlinedButton(onClick = { onChange(input.plusMinutes(minutes)) }) {
                    Text(stringResource(R.string.plus_minutes, minutes))
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        val keys = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(-1, 0, -2))
        for (row in keys) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                for (key in row) {
                    Box(Modifier.weight(1f).aspectRatio(1.8f)) {
                        when (key) {
                            -1 -> Unit
                            -2 -> FilledTonalIconButton(
                                onClick = { onChange(input.backspace()) },
                                modifier = Modifier.fillMaxSize(),
                            ) { Icon(AppIcons.Backspace, contentDescription = stringResource(R.string.backspace)) }
                            else -> FilledTonalButton(
                                onClick = { onChange(input.press(key)) },
                                modifier = Modifier.fillMaxSize(),
                            ) { Text(key.toString(), style = MaterialTheme.typography.headlineSmall) }
                        }
                    }
                }
            }
        }
    }
}

/** Редактор пресета: название, шаги, круги (SCREEN_MAP.md, п. 5). */
@Composable
private fun PresetEditor(
    initial: TimerPreset?,
    initialProgram: TimerProgram?,
    onDismiss: () -> Unit,
    onSave: (TimerPreset) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var steps by remember { mutableStateOf(initialProgram?.steps ?: emptyList()) }
    var cycles by rememberSaveable { mutableIntStateOf(initialProgram?.cycles ?: 1) }
    var sound by remember { mutableStateOf(initialProgram?.sound ?: AlarmSound.Default) }
    var stepEditing by remember { mutableStateOf<Int?>(null) }
    /** Звук чего выбирается: -1 — пресета, иначе — шага с этим номером; null — выбора нет. */
    var soundFor by remember { mutableStateOf<Int?>(null) }
    val canSave = name.isNotBlank() && steps.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.new_preset else R.string.edit_preset)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text(stringResource(R.string.preset_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.alarm_sound)) },
                        supportingContent = { Text(soundName(sound)) },
                        modifier = Modifier.clickable { soundFor = -1 },
                    )
                }
                items(steps.size) { index ->
                    StepRow(
                        index = index,
                        step = steps[index],
                        count = steps.size,
                        onEdit = { stepEditing = index },
                        onSound = { soundFor = index },
                        onChange = { updated -> steps = steps.toMutableList().also { it[index] = updated } },
                        onMove = { delta ->
                            steps = steps.toMutableList().also { list ->
                                val item = list.removeAt(index)
                                list.add(index + delta, item)
                            }
                        },
                        onDelete = { steps = steps.toMutableList().also { it.removeAt(index) } },
                    )
                }
                item {
                    TextButton(onClick = { stepEditing = steps.size }) {
                        Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.add_step))
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.cycles), modifier = Modifier.weight(1f))
                        TextButton(enabled = cycles > 1, onClick = { cycles-- }) { Text("−") }
                        Text(cycles.toString(), style = MaterialTheme.typography.titleMedium)
                        TextButton(enabled = cycles < TimerProgram.MAX_CYCLES, onClick = { cycles++ }) { Text("+") }
                    }
                }
                if (steps.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.total_duration, TimeText.duration(TimerProgram(steps, cycles).totalMillis)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = {
                onSave(
                    TimerPreset(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        name = name.trim(),
                        program = TimerProgram(steps, cycles, sound),
                        createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                    ),
                )
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    soundFor?.let { target ->
        SoundPicker(
            initial = if (target < 0) sound else steps.getOrNull(target)?.sound,
            onDismiss = { soundFor = null },
            onPick = { picked ->
                if (target < 0) {
                    sound = picked ?: AlarmSound.Default
                } else if (target < steps.size) {
                    steps = steps.toMutableList().also { it[target] = it[target].copy(sound = picked) }
                }
                soundFor = null
            },
            inheritTitle = if (target < 0) null else stringResource(R.string.sound_as_preset),
            // Тихий конец таймера — это таймер засыпания, а не пресет.
            allowSilent = false,
        )
    }
    stepEditing?.let { index ->
        StepDurationDialog(
            initialMillis = steps.getOrNull(index)?.durationMillis,
            onDismiss = { stepEditing = null },
            onConfirm = { millis ->
                stepEditing = null
                steps = steps.toMutableList().also { list ->
                    if (index < list.size) {
                        list[index] = list[index].copy(durationMillis = millis)
                    } else {
                        // Новый шаг цепочки: по умолчанию короткий сигнал; одиночный — полный.
                        list.add(TimerStep(millis, fullSignal = list.isEmpty()))
                    }
                }
            },
        )
    }
}

@Composable
private fun StepRow(
    index: Int,
    step: TimerStep,
    count: Int,
    onEdit: () -> Unit,
    onSound: () -> Unit,
    onChange: (TimerStep) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}.", modifier = Modifier.padding(end = 8.dp))
                TextButton(onClick = onEdit) { Text(TimeText.duration(step.durationMillis), style = MaterialTheme.typography.titleLarge) }
                Spacer(Modifier.weight(1f))
                IconButton(enabled = index > 0, onClick = { onMove(-1) }) {
                    Icon(AppIcons.Up, contentDescription = stringResource(R.string.move_up))
                }
                IconButton(enabled = index < count - 1, onClick = { onMove(1) }) {
                    Icon(AppIcons.Down, contentDescription = stringResource(R.string.move_down))
                }
                IconButton(onClick = onDelete) { Icon(AppIcons.Delete, contentDescription = stringResource(R.string.delete)) }
            }
            OutlinedTextField(
                value = step.name,
                onValueChange = { onChange(step.copy(name = it.take(40))) },
                label = { Text(stringResource(R.string.step_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onSound) {
                val own = step.sound
                Text(stringResource(R.string.step_sound, if (own == null) stringResource(R.string.sound_as_preset) else soundName(own)))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.signal_at_end), modifier = Modifier.weight(1f))
                FilterChip(
                    selected = !step.fullSignal,
                    onClick = { onChange(step.copy(fullSignal = false)) },
                    label = { Text(stringResource(R.string.signal_short)) },
                )
                Spacer(Modifier.size(4.dp))
                FilterChip(
                    selected = step.fullSignal,
                    onClick = { onChange(step.copy(fullSignal = true)) },
                    label = { Text(stringResource(R.string.signal_full)) },
                )
            }
        }
    }
}

@Composable
private fun StepDurationDialog(initialMillis: Long?, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var input by rememberSaveable(stateSaver = DurationInputSaver) {
        mutableStateOf(initialMillis?.let(DurationInput::ofMillis) ?: DurationInput())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { DurationEntry(input, onChange = { input = it }) },
        confirmButton = {
            TextButton(enabled = !input.isEmpty, onClick = { onConfirm(input.millis) }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Таймер засыпания (D-016, п. 1): готовые длительности и своя; в конце музыка стихает, сигнала нет. */
@Composable
private fun SleepTimerDialog(onDismiss: () -> Unit, onStart: (Long) -> Unit) {
    var custom by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable(stateSaver = DurationInputSaver) { mutableStateOf(DurationInput()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_timer)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.sleep_timer_text), style = MaterialTheme.typography.bodyMedium)
                if (custom) {
                    DurationEntry(input, onChange = { input = it })
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (minutes in SLEEP_CHOICES) {
                            FilledTonalButton(onClick = { onStart(minutes * 60_000L) }) {
                                Text(stringResource(R.string.minutes_value, minutes))
                            }
                        }
                        OutlinedButton(onClick = { custom = true }) { Text(stringResource(R.string.sleep_timer_custom)) }
                    }
                }
            }
        },
        confirmButton = {
            if (custom) {
                TextButton(enabled = !input.isEmpty, onClick = { onStart(input.millis) }) { Text(stringResource(R.string.start)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val SLEEP_CHOICES = listOf(15, 30, 45, 60, 90)

private val DurationInputSaver = Saver<DurationInput, String>(
    save = { it.digits },
    restore = { DurationInput(it) },
)
