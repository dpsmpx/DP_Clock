package com.dpsmpx.dpclock.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.delay

/**
 * Конец таймера засыпания (D-011, п. 4; D-016, п. 1): музыка и видео в других
 * приложениях плавно стихают и останавливаются. Приложение забирает аудиофокус
 * насовсем — так просят остановиться все приложения, которые соблюдают правила
 * Android, — и возвращает громкость, чтобы следующее включение не было тихим.
 */
object MediaStop {
    /** Около 4 с на стихание: приёмник будильника Android ждёт не дольше 10 с. */
    private const val FADE_STEPS = 8
    private const val FADE_STEP_MILLIS = 500L

    suspend fun fadeAndPause(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (audio.isMusicActive && !audio.isVolumeFixed) {
            for (step in 1..FADE_STEPS) {
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume * (FADE_STEPS - step) / FADE_STEPS, 0) }
                delay(FADE_STEP_MILLIS)
            }
        }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener {}
            .build()
        audio.requestAudioFocus(focus)
        // Приложениям — время остановиться, пока громкость ещё на нуле.
        delay(1_000L)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0) }
        audio.abandonAudioFocusRequest(focus)
    }
}
