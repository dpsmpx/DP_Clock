package com.dpsmpx.dpclock.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings

/**
 * Звук сигнала (D-006, п. 4): мелодия будильника телефона по умолчанию через
 * поток будильника — звучит и в «Не беспокоить», и в беззвучном режиме.
 * Своих звуковых файлов нет.
 */
object Sounds {
    private val main = Handler(Looper.getMainLooper())

    /** Короткий сигнал конца шага цепочки. */
    fun shortBeep() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull() ?: return
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
        main.postDelayed({ tone.release() }, 1_000L)
    }
}

/** Звонящий сигнал: мелодия по кругу и вибрация, пока не остановят. */
class AlarmPlayer(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private val beepLoop = object : Runnable {
        override fun run() {
            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1_000)
            main.postDelayed(this, 1_500L)
        }
    }
    private val vibratorManager = context.getSystemService(VibratorManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null

    val isPlaying: Boolean get() = player != null || tone != null

    fun start() {
        if (isPlaying) return
        wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DPClock:ring")
            .apply { acquire(MAX_RING_MILLIS) }
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(ALARM_AUDIO)
                setDataSource(context, Settings.System.DEFAULT_ALARM_ALERT_URI)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        if (player == null) {
            // Мелодии нет или она недоступна до разблокировки — тоны потока будильника.
            tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
            if (tone != null) main.post(beepLoop)
        }
        vibrate()
    }

    fun stop() {
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
        main.removeCallbacks(beepLoop)
        tone?.release()
        tone = null
        vibratorManager.defaultVibrator.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
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

        val ALARM_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
