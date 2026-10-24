package com.dpsmpx.dpclock.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.net.toUri
import com.dpsmpx.dpclock.model.AlarmSound
import kotlin.math.min
import kotlin.math.pow

/** Короткие сигналы. */
object Sounds {
    private val main = Handler(Looper.getMainLooper())

    /** Короткий сигнал конца шага цепочки. */
    fun shortBeep() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull() ?: return
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
        main.postDelayed({ tone.release() }, 1_000L)
    }
}

/** Как звучит сигнал (D-013, п. 2–4): звук, вибрация, нарастание громкости. */
data class RingSound(
    val sound: AlarmSound = AlarmSound.Default,
    val vibrate: Boolean = true,
    val rampSeconds: Int = 0,
)

/**
 * Звонящий сигнал: звук по кругу через поток будильника — звучит и в «Не
 * беспокоить», и в беззвучном режиме — и вибрация, пока не остановят.
 * Мелодия телефона или файл недоступны (удалены, до первой разблокировки) —
 * звучит собственный звук DP_Clock: он синтезируется в памяти.
 *
 * [holdWakeLock] — для сигнала; предпрослушивание в выборе мелодии обходится без него.
 */
class AlarmPlayer(private val context: Context, private val holdWakeLock: Boolean = true) {
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val vibratorManager = context.getSystemService(VibratorManager::class.java)
    private var media: MediaPlayer? = null
    private var track: AudioTrack? = null
    private var tone: ToneGenerator? = null
    private var focus: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var current: RingSound? = null
    private var startedAt = 0L

    private val beepLoop = object : Runnable {
        override fun run() {
            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1_000)
            main.postDelayed(this, 1_500L)
        }
    }

    private val ramp = object : Runnable {
        override fun run() {
            val done = applyVolume()
            if (!done) main.postDelayed(this, RAMP_STEP_MILLIS)
        }
    }

    val isPlaying: Boolean get() = current != null

    /** Звучать так; тот же звук уже звучит — ничего не меняется, нарастание не начинается заново. */
    fun play(sound: RingSound) {
        if (sound == current) return
        stopAudio()
        current = sound
        if (holdWakeLock && wakeLock == null) {
            wakeLock = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DPClock:ring")
                .apply { acquire(MAX_RING_MILLIS) }
        }
        if (sound.sound != AlarmSound.Silent) requestFocus()
        startAudio(sound.sound)
        startedAt = SystemClock.elapsedRealtime()
        if (!applyVolume()) main.postDelayed(ramp, RAMP_STEP_MILLIS)
        if (sound.vibrate) vibrate() else vibratorManager.defaultVibrator.cancel()
    }

    fun stop() {
        stopAudio()
        current = null
        vibratorManager.defaultVibrator.cancel()
        focus?.let(audioManager::abandonAudioFocusRequest)
        focus = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun startAudio(sound: AlarmSound) {
        val started = when (sound) {
            AlarmSound.Silent -> true
            AlarmSound.Default -> startMedia(Settings.System.DEFAULT_ALARM_ALERT_URI)
            is AlarmSound.Ringtone -> startMedia(sound.uri.toUri())
            is AlarmSound.File -> startMedia(sound.uri.toUri())
            is AlarmSound.Builtin -> startBuiltin(sound.id)
        }
        if (!started && !startBuiltin(Synth.DEFAULT_ID)) {
            // Последний запасной путь: тоны потока будильника.
            tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
            if (tone != null) main.post(beepLoop)
        }
    }

    private fun startMedia(uri: Uri): Boolean {
        media = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(ALARM_AUDIO)
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        return media != null
    }

    private fun startBuiltin(id: String): Boolean {
        track = runCatching {
            val pcm = pcmCache.getOrPut(id) { Synth.render(Synth.find(id)) }
            AudioTrack.Builder()
                .setAudioAttributes(ALARM_AUDIO)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Synth.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
                .apply {
                    write(pcm, 0, pcm.size)
                    setLoopPoints(0, pcm.size, -1)
                    play()
                }
        }.getOrNull()
        return track != null
    }

    private fun stopAudio() {
        main.removeCallbacks(ramp)
        media?.run {
            runCatching { stop() }
            release()
        }
        media = null
        track?.run {
            runCatching { stop() }
            release()
        }
        track = null
        main.removeCallbacks(beepLoop)
        tone?.release()
        tone = null
    }

    /** Громкость по нарастанию; true — нарастание закончилось (или его нет). */
    private fun applyVolume(): Boolean {
        val rampSeconds = current?.rampSeconds ?: 0
        val progress = if (rampSeconds <= 0) 1.0 else min(1.0, (SystemClock.elapsedRealtime() - startedAt) / (rampSeconds * 1000.0))
        // Равномерно в децибелах: от −36 дБ до полной — на слух нарастает ровно.
        val gain = if (progress >= 1.0) 1f else 10.0.pow(-36.0 * (1 - progress) / 20).toFloat()
        media?.setVolume(gain, gain)
        track?.setVolume(gain)
        return progress >= 1.0
    }

    private fun requestFocus() {
        if (focus != null) return
        // Музыка и видео в других приложениях замолкают на время сигнала.
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(ALARM_AUDIO)
            .setOnAudioFocusChangeListener {}
            .build()
            .also(audioManager::requestAudioFocus)
    }

    private fun vibrate() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)
        val vibrator = vibratorManager.defaultVibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, ALARM_AUDIO)
        }
    }

    private companion object {
        /** Предел удержания процессора, если автовыключение — «никогда». */
        const val MAX_RING_MILLIS = 60 * 60_000L
        const val RAMP_STEP_MILLIS = 200L

        val ALARM_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /** Звук DP_Clock синтезируется один раз за жизнь процесса. */
        val pcmCache = mutableMapOf<String, ShortArray>()
    }
}
