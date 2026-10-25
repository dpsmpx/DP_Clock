package com.dpsmpx.dpclock.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.dpsmpx.dpclock.model.Noise
import com.dpsmpx.dpclock.model.NoiseKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Шум в ночных часах (D-021, п. 5): круг из [Noise] по кругу, громкость
 * нарастает за 3 с. Поток — музыка: громкость — кнопками телефона. Аудиофокус —
 * чтобы таймер засыпания (D-016), закончившись, остановил и шум.
 */
class NoisePlayer(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    private var playing: NoiseKind = NoiseKind.NONE

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Начать [kind]; тот же уже играет — ничего; NONE — остановить. Вызывать с главного потока. */
    suspend fun play(kind: NoiseKind) {
        if (kind == playing) return
        stop()
        if (kind == NoiseKind.NONE) return
        val pcm = withContext(Dispatchers.Default) { synchronized(cache) { cache.getOrPut(kind) { Noise.render(kind) } } }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> stop()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> track?.pause()
                    AudioManager.AUDIOFOCUS_GAIN -> track?.play()
                }
            }, main)
            .build()
        if (audio?.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        focus = request
        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Noise.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .apply {
                    write(pcm, 0, pcm.size)
                    setLoopPoints(0, pcm.size, -1)
                    setVolume(0f)
                    play()
                }
        }.getOrNull() ?: return stop()
        track = created
        playing = kind
        fadeIn(created)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        focus?.let { audio?.abandonAudioFocusRequest(it) }
        focus = null
        playing = NoiseKind.NONE
    }

    private fun fadeIn(target: AudioTrack) {
        for (step in 1..FADE_STEPS) {
            main.postDelayed({ if (track === target) target.setVolume(step / FADE_STEPS.toFloat()) }, step * FADE_MILLIS / FADE_STEPS)
        }
    }

    private companion object {
        const val FADE_STEPS = 30
        const val FADE_MILLIS = 3000L

        /** Круг каждого шума считается один раз за жизнь процесса: ~0,7 МБ. */
        val cache = mutableMapOf<NoiseKind, ShortArray>()
    }
}
