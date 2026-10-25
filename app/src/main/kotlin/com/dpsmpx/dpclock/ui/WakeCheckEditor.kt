package com.dpsmpx.dpclock.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.PhotoSignature
import com.dpsmpx.dpclock.model.WakeCheck
import com.dpsmpx.dpclock.model.WakeLevel
import com.dpsmpx.dpclock.model.WakeMethod

/**
 * Настройка подтверждения пробуждения у будильника (D-015): способ,
 * настойчивость, число задач, образец для QR-кода и снимка, «Отложить» без
 * проверки, повторная проверка «Не спите?». Камера спрашивается, только когда
 * выбран QR-код или снимок (D-011, п. 8).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WakeCheckEditor(initial: WakeCheck, onDismiss: () -> Unit, onDone: (WakeCheck) -> Unit) {
    val context = LocalContext.current
    var check by remember { mutableStateOf(initial) }
    var capture by rememberSaveable { mutableStateOf(false) }
    var trying by rememberSaveable { mutableStateOf(false) }
    var noCamera by rememberSaveable { mutableStateOf(false) }
    var pendingMethod by rememberSaveable { mutableStateOf<WakeMethod?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val method = pendingMethod
        pendingMethod = null
        if (granted && method != null) {
            check = check.copy(method = method, reference = if (method == check.method) check.reference else "")
        } else if (!granted) {
            noCamera = true
        }
    }

    fun choose(method: WakeMethod) {
        noCamera = false
        val camera = method == WakeMethod.QR || method == WakeMethod.PHOTO
        if (camera && !context.hasCamera()) {
            pendingMethod = method
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        // Образец другого способа не подходит.
        check = check.copy(method = method, reference = if (method == check.method) check.reference else "")
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { TitleText(stringResource(R.string.wake_check)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.cancel)) }
                    },
                    actions = {
                        TextButton(enabled = check.isComplete, onClick = { onDone(check) }) { Text(stringResource(R.string.done)) }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                for (method in WakeMethod.entries) {
                    ListItem(
                        headlineContent = { Text(stringResource(methodTitle(method))) },
                        supportingContent = { Text(stringResource(methodText(method))) },
                        leadingContent = { RadioButton(selected = check.method == method, onClick = null) },
                        modifier = Modifier.clickable { choose(method) },
                    )
                }
                if (noCamera) {
                    Text(
                        stringResource(R.string.wake_no_camera),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (check.required) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    if (check.method != WakeMethod.QR) {
                        Text(
                            stringResource(R.string.wake_level),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (level in WakeLevel.entries) {
                                FilterChip(
                                    selected = check.level == level,
                                    onClick = { check = check.copy(level = level) },
                                    label = { Text(stringResource(levelTitle(check.method, level))) },
                                )
                            }
                        }
                    }
                    if (check.method == WakeMethod.MATH || check.method == WakeMethod.CODE) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.wake_tasks), modifier = Modifier.weight(1f))
                            TextButton(enabled = check.tasks > 1, onClick = { check = check.copy(tasks = check.tasks - 1) }) { Text("−") }
                            Text(check.tasks.toString(), style = MaterialTheme.typography.titleMedium)
                            TextButton(enabled = check.tasks < WakeCheck.MAX_TASKS, onClick = { check = check.copy(tasks = check.tasks + 1) }) { Text("+") }
                        }
                    }
                    if (check.method == WakeMethod.QR || check.method == WakeMethod.PHOTO) {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(if (check.method == WakeMethod.QR) R.string.wake_reference_code else R.string.wake_reference_photo))
                            },
                            supportingContent = {
                                Text(
                                    when {
                                        check.reference.isEmpty() -> stringResource(R.string.wake_reference_missing)
                                        check.method == WakeMethod.QR -> check.reference
                                        else -> stringResource(R.string.wake_reference_photo_ready)
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                FilledTonalButton(onClick = { capture = true }) {
                                    Text(stringResource(if (check.method == WakeMethod.QR) R.string.wake_scan else R.string.wake_take))
                                }
                            },
                        )
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.wake_snooze_free)) },
                        trailingContent = { Switch(checked = check.snoozeAllowed, onCheckedChange = null) },
                        modifier = Modifier.toggleable(value = check.snoozeAllowed, role = Role.Switch) { check = check.copy(snoozeAllowed = it) },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.recheck),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
                )
                Text(
                    stringResource(R.string.recheck_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (minutes in WakeCheck.RECHECK_CHOICES) {
                        FilterChip(
                            selected = check.recheckMinutes == minutes,
                            onClick = { check = check.copy(recheckMinutes = minutes) },
                            label = { Text(if (minutes == 0) stringResource(R.string.ramp_off) else stringResource(R.string.minutes_value, minutes)) },
                        )
                    }
                }
                if (check.required && check.isComplete) {
                    TextButton(onClick = { trying = true }, modifier = Modifier.padding(16.dp)) { Text(stringResource(R.string.wake_try)) }
                }
            }
        }
    }

    if (capture) {
        ReferenceCapture(
            method = check.method,
            onDismiss = { capture = false },
            onCaptured = { reference ->
                capture = false
                check = check.copy(reference = reference)
            },
        )
    }
    if (trying) {
        Dialog(onDismissRequest = { trying = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                WakeChallenge(check, onInteract = {}, onPassed = { trying = false }, onBack = { trying = false })
            }
        }
    }
}

/** Снять образец: QR-код — первый найденный; снимок — по кнопке, если в кадре есть детали. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReferenceCapture(method: WakeMethod, onDismiss: () -> Unit, onCaptured: (String) -> Unit) {
    var latest by remember { mutableStateOf<IntArray?>(null) }
    var failed by remember { mutableStateOf(false) }
    var flat by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { TitleText(stringResource(if (method == WakeMethod.QR) R.string.wake_reference_code else R.string.wake_reference_photo)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.cancel)) }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(if (method == WakeMethod.QR) R.string.wake_reference_code_hint else R.string.wake_reference_photo_hint))
                if (failed) {
                    Text(stringResource(R.string.wake_camera_failed), color = MaterialTheme.colorScheme.error)
                } else {
                    CameraScanner(
                        mode = if (method == WakeMethod.QR) CameraMode.CODE else CameraMode.PHOTO,
                        onResult = { found ->
                            when (found) {
                                is CameraResult.Code -> onCaptured(found.text)
                                is CameraResult.Signature -> latest = found.value
                            }
                        },
                        onError = { failed = true },
                        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                    )
                }
                if (flat) Text(stringResource(R.string.wake_reference_flat), color = MaterialTheme.colorScheme.error)
                if (method == WakeMethod.PHOTO) {
                    Button(enabled = latest != null, onClick = {
                        val signature = latest ?: return@Button
                        if (PhotoSignature.detail(signature) < PhotoSignature.MIN_DETAIL) flat = true
                        else onCaptured(PhotoSignature.encode(signature))
                    }) { Text(stringResource(R.string.wake_take)) }
                }
            }
        }
    }
}

private fun methodTitle(method: WakeMethod): Int = when (method) {
    WakeMethod.NONE -> R.string.wake_none
    WakeMethod.MATH -> R.string.wake_math
    WakeMethod.CODE -> R.string.wake_code
    WakeMethod.SHAKE -> R.string.wake_shake_title
    WakeMethod.QR -> R.string.wake_qr
    WakeMethod.PHOTO -> R.string.wake_photo
}

private fun methodText(method: WakeMethod): Int = when (method) {
    WakeMethod.NONE -> R.string.wake_none_text
    WakeMethod.MATH -> R.string.wake_math_text
    WakeMethod.CODE -> R.string.wake_code_text
    WakeMethod.SHAKE -> R.string.wake_shake_text
    WakeMethod.QR -> R.string.wake_qr_text
    WakeMethod.PHOTO -> R.string.wake_photo_text
}

private fun levelTitle(method: WakeMethod, level: WakeLevel): Int = when (method) {
    WakeMethod.SHAKE -> when (level) {
        WakeLevel.EASY -> R.string.wake_shakes_easy
        WakeLevel.MEDIUM -> R.string.wake_shakes_medium
        WakeLevel.HARD -> R.string.wake_shakes_hard
    }
    WakeMethod.CODE -> when (level) {
        WakeLevel.EASY -> R.string.wake_code_easy
        WakeLevel.MEDIUM -> R.string.wake_code_medium
        WakeLevel.HARD -> R.string.wake_code_hard
    }
    else -> when (level) {
        WakeLevel.EASY -> R.string.wake_level_easy
        WakeLevel.MEDIUM -> R.string.wake_level_medium
        WakeLevel.HARD -> R.string.wake_level_hard
    }
}

/** Кратко для редактора будильника: способ и повторная проверка. */
@Composable
fun wakeSummary(check: WakeCheck): String {
    val method = stringResource(methodTitle(check.method))
    return if (check.recheckMinutes > 0) method + " · " + stringResource(R.string.recheck_in, check.recheckMinutes) else method
}
