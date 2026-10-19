package com.dpsmpx.dpclock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** Единственный экран версии 0.0.0 — цифровые часы в круге (D-004). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DpClockTheme {
                ClockScreen()
            }
        }
    }
}
