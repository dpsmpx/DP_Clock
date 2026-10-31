package com.dpsmpx.dpclock.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Раскладка по ширине окна (D-022, п. 2; выбор автора — боковая панель и две
 * колонки): разделы слева — с 600 dp (планшет, раскрытый складной, телефон
 * боком); список и редактор рядом, настройки в две колонки — с 720 dp.
 * Ширина — окна, а не экрана: в разделённом экране раскладка как на телефоне.
 */
data class WindowLayout(val rail: Boolean, val twoPane: Boolean)

@Composable
fun rememberWindowLayout(): WindowLayout {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return WindowLayout(rail = width >= RAIL_WIDTH, twoPane = width >= TWO_PANE_WIDTH)
}

/** Правая колонка, пока в ней ничего не открыто: подсказка по центру. */
@Composable
fun EmptyPane(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Крупное время в одну строку (D-022, п. 4): с крупным шрифтом системы растёт
 * вместе с ним, пока влезает, дальше уменьшается — не переносится и не обрезается.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Unspecified,
    minFontSize: TextUnit = 12.sp,
) {
    val content = LocalContentColor.current
    BasicText(
        text,
        modifier,
        style = style.copy(color = color.takeOrElse { style.color.takeOrElse { content } }, textAlign = textAlign),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = minFontSize, maxFontSize = style.fontSize),
    )
}

/** Заголовок экрана в верхней панели — для TalkBack заголовок: к нему переходят жестом (D-022, п. 3). */
@Composable
fun TitleText(text: String) {
    Text(text, modifier = Modifier.semantics { heading() })
}

private val RAIL_WIDTH = 600.dp
private val TWO_PANE_WIDTH = 720.dp
