package com.dpsmpx.dpclock

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Тема Material 3: цвета — от обоев (Material You), светлая или тёмная —
 * как в системе (D-004). minSdk 31, поэтому динамические цвета есть на
 * любом поддерживаемом устройстве и запасная палитра не нужна.
 */
@Composable
fun DpClockTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
