package com.dpsmpx.dpclock.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.update.UpdateChecker
import com.dpsmpx.dpclock.update.UpdateDownloader
import com.dpsmpx.dpclock.update.UpdatePrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val AUTO_HIDE_MS = 30_000L
private const val SWIPE_DISMISS_PX = 120f

/** Строки обновлений: RU по умолчанию, EN если язык не русский. */
private class UpdateStrings(private val ru: Boolean) {
    val check = if (ru) "Проверять обновления" else "Check for updates"
    val checkText = if (ru) "Раз в сутки спрашивать у GitHub, не вышла ли новая версия. Никаких сведений о вас в запросе нет." else "Once a day, ask GitHub if a new version is out. Nothing about you is sent."
    val now = if (ru) "Проверить сейчас" else "Check now"
    val nowText = if (ru) "Сверить с последним выпуском на GitHub" else "Compare with the latest GitHub release"
    val action = if (ru) "Обновить" else "Update"
    val dismiss = if (ru) "Скрыть" else "Hide"
    fun available(v: String) = if (ru) "Доступна версия $v" else "Version $v is available"
    val checking = if (ru) "Проверка…" else "Checking…"
    fun downloading(p: Int) = if (ru) "Скачивание… $p%" else "Downloading… $p%"
    val upToDate = if (ru) "У вас уже последняя версия" else "You already have the latest version"
    val failed = if (ru) "Не удалось проверить или скачать обновление" else "Could not check or download the update"
    val needPermission = if (ru) "Разрешите установку из DP Clock, затем снова нажмите «Обновить»" else "Allow installing apps from DP Clock, then tap Update again"
}

@Composable
private fun rememberUpdateStrings(): UpdateStrings {
    val lang = LocalConfiguration.current.locales[0].language
    return remember(lang) { UpdateStrings(lang == "ru") }
}

@Composable
fun UpdateBannerHost(snackbar: SnackbarHostState? = null) {
    val context = LocalContext.current
    val s = rememberUpdateStrings()
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
                .offset { IntOffset(offsetX.toInt(), 0) }
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
                    s.available(info.version),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { prefs.dismiss(); visible = false },
                        enabled = !downloading,
                    ) { Text(s.dismiss) }
                    TextButton(
                        onClick = {
                            if (downloading) return@TextButton
                            if (!info.url.endsWith(".apk")) {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, info.url.toUri())
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
                                        snackbar?.showSnackbar(s.needPermission)
                                        UpdateDownloader.openInstallPermissionSettings(context)
                                    }
                                    is UpdateDownloader.Outcome.Failed -> {
                                        downloading = false
                                        snackbar?.showSnackbar(s.failed)
                                    }
                                }
                            }
                        },
                        enabled = !downloading,
                    ) {
                        Text(if (downloading) s.downloading(progress) else s.action)
                    }
                }
            }
        }
    }
}

@Composable
fun RememberUpdateCheck() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        UpdateChecker.checkIfDue(context)
    }
}

@Composable
fun UpdateSettingsItems(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val s = rememberUpdateStrings()
    val prefs = remember { UpdatePrefs.get(context) }
    val enabled by prefs.enabled.collectAsStateWithLifecycle()
    val available by prefs.available.collectAsStateWithLifecycle()
    val update = remember(available) { UpdateChecker.pending(context, available) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    ListItem(
        headlineContent = { Text(s.check) },
        supportingContent = { Text(s.checkText) },
        trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = enabled, role = Role.Switch) { on -> prefs.setEnabled(on) },
    )
    ListItem(
        headlineContent = { Text(s.now) },
        supportingContent = {
            Text(
                when {
                    downloading -> s.downloading(progress)
                    checking -> s.checking
                    update != null -> s.available(update.version)
                    else -> s.nowText
                },
            )
        },
        modifier = Modifier.clickable(enabled = !checking && !downloading) {
            scope.launch {
                checking = true
                when (UpdateChecker.check(context, manual = true)) {
                    UpdateChecker.Result.UPDATE_AVAILABLE -> Unit
                    UpdateChecker.Result.UP_TO_DATE -> snackbar.showSnackbar(s.upToDate)
                    UpdateChecker.Result.FAILED -> snackbar.showSnackbar(s.failed)
                }
                checking = false
            }
        },
    )
    if (update != null) {
        ListItem(
            headlineContent = { Text(s.action) },
            supportingContent = { Text(s.available(update.version)) },
            modifier = Modifier.clickable(enabled = !downloading) {
                if (!update.url.endsWith(".apk")) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, update.url.toUri())
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
                            snackbar.showSnackbar(s.needPermission)
                            UpdateDownloader.openInstallPermissionSettings(context)
                        }
                        is UpdateDownloader.Outcome.Failed -> {
                            downloading = false
                            snackbar.showSnackbar(s.failed)
                        }
                    }
                }
            },
        )
    }
}
