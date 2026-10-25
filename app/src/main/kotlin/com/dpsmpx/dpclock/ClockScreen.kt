package com.dpsmpx.dpclock

import android.content.Intent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.ui.AppIcons
import com.dpsmpx.dpclock.ui.ClockBackdrop
import com.dpsmpx.dpclock.ui.ClockFace
import com.dpsmpx.dpclock.ui.clockSide
import com.dpsmpx.dpclock.ui.isShown

/** Сторона часов — доля меньшей стороны окна. */
private const val FACE_FRACTION = 0.7f

/**
 * Раздел «Часы»: текущее время по центру в выбранном стиле (D-004; D-021, п. 4),
 * под ним — фон (D-021, п. 6); внизу — «Ночные часы» (D-017, п. 4).
 */
@Composable
fun ClockScreen(modifier: Modifier = Modifier, visible: Boolean = true) {
    val context = LocalContext.current
    val settings by DpClockApp.graph(context).repository.settings.collectAsStateWithLifecycle(Settings())
    val background = settings.appearance.background
    val backdrop = background.isShown(context)
    Surface(modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            if (backdrop) ClockBackdrop(background, active = visible)
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            ClockFace(
                settings.appearance,
                night = false,
                size = clockSide(maxWidth, maxHeight, FACE_FRACTION),
                active = visible,
                onBackdrop = backdrop,
            )
            TextButton(
                onClick = { context.startActivity(Intent(context, NightClockActivity::class.java)) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                colors = if (backdrop) ButtonDefaults.textButtonColors(contentColor = Color.White) else ButtonDefaults.textButtonColors(),
            ) {
                Icon(AppIcons.Moon, contentDescription = null)
                Text(stringResource(R.string.night_clock), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
