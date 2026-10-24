package com.dpsmpx.dpclock.platform

import android.app.UiModeManager
import android.content.Context
import androidx.core.content.edit
import com.dpsmpx.dpclock.model.Appearance
import com.dpsmpx.dpclock.model.ThemeMode

/**
 * Тема на уровне Android (D-021, п. 2): ночной режим приложения задаётся через
 * `UiModeManager` (Android 12+) — окно, системные панели и Compose меняются
 * вместе, система помнит выбор между запусками. Тема и цвет копируются в
 * настройки приложения: первый кадр рисуется в них, не дожидаясь базы.
 */
object Appearances {
    private const val PREFS = "appearance"
    private const val THEME = "theme"
    private const val SEED = "seed"

    fun apply(context: Context, appearance: Appearance) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(THEME, appearance.theme.name)
            val seed = appearance.seedColor
            if (seed == null) remove(SEED) else putInt(SEED, seed)
        }
        val mode = when (appearance.theme) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK, ThemeMode.BLACK -> UiModeManager.MODE_NIGHT_YES
        }
        // Тот же режим система не применяет второй раз — проверять заранее не нужно.
        runCatching { context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode) }
    }

    /** Тема и цвет последнего запуска — до первого чтения базы. */
    fun cached(context: Context): Appearance {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Appearance(
            theme = ThemeMode.entries.firstOrNull { it.name == prefs.getString(THEME, null) } ?: ThemeMode.SYSTEM,
            seedColor = if (prefs.contains(SEED)) prefs.getInt(SEED, 0) else null,
        )
    }
}
