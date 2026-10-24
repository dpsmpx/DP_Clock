package com.dpsmpx.dpclock.ui

import android.media.RingtoneManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.AlarmSound
import com.dpsmpx.dpclock.platform.AlarmPlayer
import com.dpsmpx.dpclock.platform.RingSound
import com.dpsmpx.dpclock.platform.Ringtones
import com.dpsmpx.dpclock.platform.Synth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Выбор звука будильника (D-013, п. 2): звуки DP_Clock, мелодия будильника
 * телефона, мелодии телефона, свой файл, без звука. Нажатие — предпрослушивание
 * через поток будильника, с той громкостью, с какой прозвучит сигнал.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundPicker(initial: AlarmSound, onDismiss: () -> Unit, onPick: (AlarmSound) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(initial) }
    val player = remember { AlarmPlayer(context.applicationContext, holdWakeLock = false) }
    DisposableEffect(player) { onDispose { player.stop() } }

    fun choose(sound: AlarmSound) {
        selected = sound
        if (sound == AlarmSound.Silent) player.stop() else player.play(RingSound(sound, vibrate = false))
    }

    val phone by produceState<Pair<List<Ringtones.Entry>, List<Ringtones.Entry>>?>(null) {
        value = withContext(Dispatchers.IO) {
            Ringtones.list(context, RingtoneManager.TYPE_ALARM) to Ringtones.list(context, RingtoneManager.TYPE_RINGTONE)
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) choose(AlarmSound.File(uri.toString(), Ringtones.keepFile(context, uri)))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.alarm_sound)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.cancel)) }
                    },
                    actions = { TextButton(onClick = { onPick(selected) }) { Text(stringResource(R.string.done)) } },
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                header(R.string.sounds_dp_clock)
                items(Synth.catalog, key = { "builtin:" + it.id }) { sound ->
                    SoundRow(stringResource(builtinName(sound.id)), selected == AlarmSound.Builtin(sound.id)) {
                        choose(AlarmSound.Builtin(sound.id))
                    }
                }
                header(R.string.sounds_phone)
                item(key = "default") {
                    SoundRow(stringResource(R.string.sound_default), selected == AlarmSound.Default) { choose(AlarmSound.Default) }
                }
                val (alarms, ringtones) = phone ?: (emptyList<Ringtones.Entry>() to emptyList())
                items(alarms, key = { "alarm:" + it.uri }) { entry ->
                    SoundRow(entry.title, (selected as? AlarmSound.Ringtone)?.uri == entry.uri) {
                        choose(AlarmSound.Ringtone(entry.uri, entry.title))
                    }
                }
                if (ringtones.isNotEmpty()) header(R.string.sounds_ringtones)
                items(ringtones, key = { "ringtone:" + it.uri }) { entry ->
                    SoundRow(entry.title, (selected as? AlarmSound.Ringtone)?.uri == entry.uri) {
                        choose(AlarmSound.Ringtone(entry.uri, entry.title))
                    }
                }
                header(R.string.sounds_other)
                item(key = "file") {
                    val file = selected as? AlarmSound.File ?: initial as? AlarmSound.File
                    SoundRow(
                        title = stringResource(R.string.sound_file),
                        selected = selected is AlarmSound.File,
                        supporting = file?.title,
                    ) {
                        if (file != null && selected !is AlarmSound.File) choose(file) else pickFile.launch(arrayOf("audio/*"))
                    }
                }
                item(key = "silent") {
                    SoundRow(stringResource(R.string.sound_silent), selected == AlarmSound.Silent) { choose(AlarmSound.Silent) }
                }
            }
        }
    }
}

private fun LazyListScope.header(text: Int) {
    item(key = "header:$text") {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun SoundRow(title: String, selected: Boolean, supporting: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Название звука для списка и редактора. */
@Composable
fun soundName(sound: AlarmSound): String = when (sound) {
    AlarmSound.Default -> stringResource(R.string.sound_default)
    AlarmSound.Silent -> stringResource(R.string.sound_silent)
    is AlarmSound.Builtin -> stringResource(builtinName(sound.id))
    is AlarmSound.Ringtone -> sound.title
    is AlarmSound.File -> sound.title
}

/** Названия звуков DP_Clock — по id из каталога `Synth`. */
fun builtinName(id: String): Int = when (id) {
    "dawn" -> R.string.sound_dawn
    "marimba" -> R.string.sound_marimba
    "classic" -> R.string.sound_classic
    "pulse" -> R.string.sound_pulse
    else -> R.string.sound_chime
}
