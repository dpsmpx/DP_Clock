package com.dpsmpx.dpclock.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.update.UpdateChecker
import com.dpsmpx.dpclock.update.UpdateDownloader
import com.dpsmpx.dpclock.update.UpdateInfo
import com.dpsmpx.dpclock.update.UpdatePrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Автоскрытие баннера, как у всплывающего уведомления. */
private const val AUTO_HIDE_MS = 30_000L
private const val SWIPE_DISMISS_PX = 120f

/**
 * Небольшой баннер поверх интерфейса (не сдвигает раскладку): «Обновить» / «Скрыть»,
 * смахивание в сторону, сам исчезает через 30 с.
 */
@Composable
fun UpdateBannerHost(snackbar: SnackbarHostState? = null) {
    val context = LocalContext.current
    val prefs = remember { UpdatePrefs.get(context) }
    val available by prefs.available.collectAsStateWithLifecycle()
    val update = remember(available) { UpdateChecker.pending(context, available) }
    var visible by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(update?.version) {
        visible = update != null
        offsetX = 0f
        if (update != null) {
            delay(AUTO_HIDE_MS)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible && update != null,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically(),
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .zIndex(8f),
    ) {
        val info = update ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            tonalElevation = 4.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(info.version) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(offsetX) > SWIPE_DISMISS_PX) {
                                prefs.dismiss()
                                visible = false
                            }
                            offsetX = 0f
                        },
                        onHorizontalDrag = { _, drag -> offsetX += drag },
                    )
                },
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    stringResource(R.string.update_available, info.version),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            prefs.dismiss()
                            visible = false
                        },
                        enabled = !downloading,
                    ) { Text(stringResource(R.string.update_dismiss)) }
                    TextButton(
                        onClick = {
                            if (downloading) return@TextButton
                            if (!info.url.endsWith(".apk")) {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(info.url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                                return@TextButton
                            }
                            downloading = true
                            progress = 0
                            scope.launch {
                                when (val result = UpdateDownloader.downloadAndInstall(context, info.url) { progress = it }) {
                                    is UpdateDownloader.Outcome.Ready -> {
                                        downloading = false
                                        visible = false
                                    }
                                    is UpdateDownloader.Outcome.NeedInstallPermission -> {
                                        downloading = false
                                        snackbar?.showSnackbar(context.getString(R.string.update_need_permission))
                                        UpdateDownloader.openInstallPermissionSettings(context)
                                    }
                                    is UpdateDownloader.Outcome.Failed -> {
                                        downloading = false
                                        snackbar?.showSnackbar(context.getString(R.string.update_failed))
                                    }
                                }
                            }
                        },
                        enabled = !downloading,
                    ) {
                        Text(
                            if (downloading) stringResource(R.string.update_downloading, progress)
                            else stringResource(R.string.update_action),
                        )
                    }
                }
            }
        }
    }
}

/** Плановая проверка при открытии приложения и показ баннера при новой версии. */
@Composable
fun RememberUpdateCheck() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        UpdateChecker.checkIfDue(context)
    }
}

/** Блок в «О приложении»: переключатель, проверка, скачивание. */
@Composable
fun UpdateSettingsItems(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val prefs = remember { UpdatePrefs.get(context) }
    val enabled by prefs.enabled.collectAsStateWithLifecycle()
    val available by prefs.available.collectAsStateWithLifecycle()
    val update = remember(available) { UpdateChecker.pending(context, available) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val upToDate = stringResource(R.string.update_up_to_date)
    val failed = stringResource(R.string.update_failed)

    androidx.compose.material3.ListItem(
        headlineContent = { Text(stringResource(R.string.update_check)) },
        supportingContent = { Text(stringResource(R.string.update_check_text)) },
        trailingContent = {
            androidx.compose.material3.Switch(checked = enabled, onCheckedChange = null)
        },
        modifier = androidx.compose.foundation.selection.toggleable(
            value = enabled,
            role = androidx.compose.ui.semantics.Role.Switch,
        ) { on -> prefs.setEnabled(on) },
    )
    androidx.compose.material3.ListItem(
        headlineContent = { Text(stringResource(R.string.update_now)) },
        supportingContent = {
            Text(
                when {
                    downloading -> stringResource(R.string.update_downloading, progress)
                    checking -> stringResource(R.string.update_checking)
                    update != null -> stringResource(R.string.update_available, update.version)
                    else -> stringResource(R.string.update_now_text)
                },
            )
        },
        modifier = androidx.compose.foundation.clickable(enabled = !checking && !downloading) {
            scope.launch {
                checking = true
                when (UpdateChecker.check(context, manual = true)) {
                    UpdateChecker.Result.UPDATE_AVAILABLE -> Unit
                    UpdateChecker.Result.UP_TO_DATE -> snackbar.showSnackbar(upToDate)
                    UpdateChecker.Result.FAILED -> snackbar.showSnackbar(failed)
                }
                checking = false
            }
        },
    )
    if (update != null) {
        androidx.compose.material3.ListItem(
            headlineContent = { Text(stringResource(R.string.update_action)) },
            supportingContent = { Text(stringResource(R.string.update_available, update.version)) },
            modifier = androidx.compose.foundation.clickable(enabled = !downloading) {
                if (!update.url.endsWith(".apk")) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(update.url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    return@clickable
                }
                downloading = true
                progress = 0
                scope.launch {
                    when (val result = UpdateDownloader.downloadAndInstall(context, update.url) { progress = it }) {
                        is UpdateDownloader.Outcome.Ready -> downloading = false
                        is UpdateDownloader.Outcome.NeedInstallPermission -> {
                            downloading = false
                            snackbar.showSnackbar(context.getString(R.string.update_need_permission))
                            UpdateDownloader.openInstallPermissionSettings(context)
                        }
                        is UpdateDownloader.Outcome.Failed -> {
                            downloading = false
                            snackbar.showSnackbar(failed)
                        }
                    }
                }
            },
        )
    }
}
