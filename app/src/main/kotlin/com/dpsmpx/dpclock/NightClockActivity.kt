package com.dpsmpx.dpclock

import android.media.AudioManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.dpsmpx.dpclock.platform.NoisePlayer
import com.dpsmpx.dpclock.ui.NightClock
import kotlinx.coroutines.awaitCancellation

/**
 * Ночные часы во весь экран (D-017, п. 4): экран не гаснет, яркость — почти
 * ноль; нажатие — чуть ярче и обратно, тогда же внизу — выбор шума (D-021, п. 5).
 * Шум звучит, пока экран открыт. Видны и поверх блокировки.
 */
class NightClockActivity : ComponentActivity() {
    private val noise by lazy { NoisePlayer(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Кнопки громкости — громкость шума.
        volumeControlStream = AudioManager.STREAM_MUSIC
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            var bright by rememberSaveable { mutableStateOf(false) }
            SideEffect {
                window.attributes = window.attributes.apply { screenBrightness = if (bright) BRIGHT else DIM }
            }
            val settings by DpClockApp.graph(this).repository.settings.collectAsStateWithLifecycle(null)
            val kind = settings?.appearance?.nightNoise
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(kind) {
                if (kind == null) return@LaunchedEffect
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        noise.play(kind)
                        awaitCancellation()
                    } finally {
                        noise.stop()
                    }
                }
            }
            NightClock(Modifier.clickable { bright = !bright }, controls = bright)
        }
    }

    private companion object {
        const val DIM = 0.01f
        const val BRIGHT = 0.3f
    }
}
