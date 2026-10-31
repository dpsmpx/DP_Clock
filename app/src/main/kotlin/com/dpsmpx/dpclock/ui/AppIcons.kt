package com.dpsmpx.dpclock.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Собственные значки DP_Clock: простые линии на сетке 24×24, нарисованы для
 * проекта (D-003) — без сторонних наборов значков.
 */
object AppIcons {
    private const val CIRCLE_7_AT_12_13 = "M5 13a7 7 0 1 0 14 0a7 7 0 1 0 -14 0"

    val Alarm = icon("Alarm", "$CIRCLE_7_AT_12_13 M12 9.5V13l2.5 2 M3.5 6.5L7 3.5 M20.5 6.5L17 3.5")
    val Timer = icon("Timer", "M7 3h10 M7 21h10 M8 3v3.5l4 5.5l-4 5.5V21 M16 3v3.5l-4 5.5l4 5.5V21")
    val Stopwatch = icon("Stopwatch", "$CIRCLE_7_AT_12_13 M10 2.5h4 M12 2.5V6 M12 13l3 -3 M18 6.5l1.5 -1.5")
    val Clock = icon("Clock", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0 M12 7v5l3.5 2")
    val Settings = icon("Settings", "M4 7h4 M12 7h8 M10 5v4 M4 17h10 M18 17h2 M16 15v4")
    val Sort = icon("Sort", "M4 7h16 M4 12h11 M4 17h6")
    val Chart = icon("Chart", "M4 20h16 M7 16v-4 M12 16V7 M17 16v-7")
    val Add = icon("Add", "M12 5v14 M5 12h14")
    val Remove = icon("Remove", "M5 12h14")
    val Play = icon("Play", "M8 5l11 7l-11 7z", filled = true)
    val Pause = icon("Pause", "M9 5v14 M15 5v14", width = 3f)
    val Stop = icon("Stop", "M6.5 6.5h11v11h-11z", filled = true)
    val Lap = icon("Lap", "M6 21V4 M6 4h11l-2 4l2 4H6")
    val Reset = icon("Reset", "M19 12a7 7 0 1 1 -2.05 -4.95 M17.5 3v4.5H13")
    val Delete = icon("Delete", "M5 7h14 M10 4h4 M7 7l1 13h8l1 -13 M10.5 10.5v6 M13.5 10.5v6")
    val Close = icon("Close", "M6 6l12 12 M18 6L6 18")
    val Back = icon("Back", "M19 12H5 M11 6l-6 6l6 6")
    val Backspace = icon("Backspace", "M9 6h11v12H9l-6 -6z M12 9.5l5 5 M17 9.5l-5 5")
    val Keyboard = icon("Keyboard", "M3.5 6.5h17v11h-17z M7 10h1 M11 10h2 M16 10h1 M8 14h8")
    val Up = icon("Up", "M12 19V5 M6 11l6 -6l6 6")
    val Down = icon("Down", "M12 5v14 M6 13l6 6l6 -6")
    val More = icon("More", "M12 6v0.01 M12 12v0.01 M12 18v0.01", width = 3f)
    val Moon = icon("Moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val Sun = icon(
        "Sun",
        "M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0 M12 2.5v2 M12 19.5v2 M2.5 12h2 M19.5 12h2 " +
            "M5.3 5.3l1.4 1.4 M17.3 17.3l1.4 1.4 M5.3 18.7l1.4 -1.4 M17.3 6.7l1.4 -1.4",
    )
    val Globe = icon(
        "Globe",
        "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0 M3.5 9h17 M3.5 15h17 M12 3c-3 3.5 -3 14.5 0 18 M12 3c3 3.5 3 14.5 0 18",
    )
    val Place = icon("Place", "M12 21s-6.5 -6 -6.5 -11a6.5 6.5 0 0 1 13 0c0 5 -6.5 11 -6.5 11z M10 10a2 2 0 1 0 4 0a2 2 0 1 0 -4 0")
    val Search = icon("Search", "M4 10.5a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0 M15.5 15.5L20 20")
    val Share = icon(
        "Share",
        "M15 5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M3 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M15 19a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 " +
            "M8.6 13.5l6.8 4 M15.4 6.5l-6.8 4",
    )

    private fun icon(name: String, path: String, filled: Boolean = false, width: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(path),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
}
