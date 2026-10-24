package com.dpsmpx.dpclock

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.model.Palette
import com.dpsmpx.dpclock.model.ThemeMode
import com.dpsmpx.dpclock.platform.Appearances

/**
 * Тема Material 3 (D-004; D-021): цвета — от обоев (Material You) или из
 * выбранного цвета; светлая, тёмная или чёрная — как выбрано, по умолчанию как
 * в системе. Светлая или тёмная — ночной режим приложения ([Appearances]).
 */
@Composable
fun DpClockTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val initial = remember { Appearances.cached(context) }
    val settings by DpClockApp.graph(context).repository.settings.collectAsStateWithLifecycle(null)
    val appearance = settings?.appearance ?: initial
    val dark = isSystemInDarkTheme()
    val black = dark && appearance.theme == ThemeMode.BLACK
    val seed = appearance.seedColor
    val colorScheme = remember(seed, dark, black) {
        when {
            seed != null -> Palette.scheme(seed, dark, black).toColorScheme(dark)
            dark && black -> dynamicDarkColorScheme(context).blackened()
            dark -> dynamicDarkColorScheme(context)
            else -> dynamicLightColorScheme(context)
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** Чёрная тема из цветов обоев: фон — чистый чёрный, карточки — чуть светлее. */
private fun ColorScheme.blackened(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1B1B1B),
    surfaceContainerHighest = Color(0xFF242424),
    surfaceBright = Color(0xFF2C2C2C),
)

private fun Palette.Scheme.toColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Color(primary), onPrimary = Color(onPrimary),
        primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer), inversePrimary = Color(inversePrimary),
        secondary = Color(secondary), onSecondary = Color(onSecondary),
        secondaryContainer = Color(secondaryContainer), onSecondaryContainer = Color(onSecondaryContainer),
        tertiary = Color(tertiary), onTertiary = Color(onTertiary),
        tertiaryContainer = Color(tertiaryContainer), onTertiaryContainer = Color(onTertiaryContainer),
        background = Color(background), onBackground = Color(onBackground),
        surface = Color(surface), onSurface = Color(onSurface), surfaceVariant = Color(surfaceVariant), onSurfaceVariant = Color(onSurfaceVariant),
        surfaceTint = Color(primary), inverseSurface = Color(inverseSurface), inverseOnSurface = Color(inverseOnSurface),
        error = Color(error), onError = Color(onError), errorContainer = Color(errorContainer), onErrorContainer = Color(onErrorContainer),
        outline = Color(outline), outlineVariant = Color(outlineVariant), scrim = Color(scrim),
        surfaceBright = Color(surfaceBright), surfaceDim = Color(surfaceDim),
        surfaceContainer = Color(surfaceContainer), surfaceContainerHigh = Color(surfaceContainerHigh),
        surfaceContainerHighest = Color(surfaceContainerHighest), surfaceContainerLow = Color(surfaceContainerLow),
        surfaceContainerLowest = Color(surfaceContainerLowest),
        primaryFixed = Color(primaryFixed), primaryFixedDim = Color(primaryFixedDim),
        onPrimaryFixed = Color(onPrimaryFixed), onPrimaryFixedVariant = Color(onPrimaryFixedVariant),
        secondaryFixed = Color(secondaryFixed), secondaryFixedDim = Color(secondaryFixedDim),
        onSecondaryFixed = Color(onSecondaryFixed), onSecondaryFixedVariant = Color(onSecondaryFixedVariant),
        tertiaryFixed = Color(tertiaryFixed), tertiaryFixedDim = Color(tertiaryFixedDim),
        onTertiaryFixed = Color(onTertiaryFixed), onTertiaryFixedVariant = Color(onTertiaryFixedVariant),
    )
}
