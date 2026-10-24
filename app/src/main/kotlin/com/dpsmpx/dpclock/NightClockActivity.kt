package com.dpsmpx.dpclock

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dpsmpx.dpclock.ui.NightClock

/**
 * Ночные часы во весь экран (D-017, п. 4): экран не гаснет, яркость — почти
 * ноль; нажатие — чуть ярче и обратно. Видны и поверх блокировки.
 */
class NightClockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
            NightClock(Modifier.clickable { bright = !bright })
        }
    }

    private companion object {
        const val DIM = 0.01f
        const val BRIGHT = 0.3f
    }
}
