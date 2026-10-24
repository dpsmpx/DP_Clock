package com.dpsmpx.dpclock.model

/** Настройки (DATA_MODEL.md, п. 4; D-010, D-013). */
data class Settings(
    val snoozeMinutes: Int = DEFAULT_SNOOZE,
    /** Через сколько минут сигнал стихает сам; null — никогда. */
    val autoStopMinutes: Int? = DEFAULT_AUTO_STOP,
    val alarmOrder: AlarmOrder = AlarmOrder.TIME_OF_DAY,
    /** Человек отметил, что настроил автозапуск и батарею на Xiaomi (SCREEN_MAP.md, п. 10). */
    val xiaomiHintsDone: Boolean = false,
    val signalStyle: SignalStyle = SignalStyle.FULL_SCREEN,
    val volumeButtons: VolumeButtons = VolumeButtons.SNOOZE,
) {
    companion object {
        const val DEFAULT_SNOOZE = 10
        const val DEFAULT_AUTO_STOP = 10
        val SNOOZE_CHOICES = listOf(1, 5, 10, 15, 20, 30)
        /** null — «никогда». */
        val AUTO_STOP_CHOICES: List<Int?> = listOf(1, 5, 10, 15, 20, 30, null)
        /** Нарастание громкости, с; 0 — выключено, по умолчанию (D-013, п. 4). */
        val RAMP_CHOICES = listOf(0, 15, 30, 60, 120, 300)
    }
}

/** Вид сигнала будильника и таймера (D-007, п. 2). */
enum class SignalStyle {
    /** Полный экран поверх блокировки; если телефоном пользуются — всплывающее уведомление. */
    FULL_SCREEN,

    /** Всегда только уведомление с кнопками. */
    NOTIFICATION,
}

/** Что делают кнопки громкости, пока звонит сигнал (D-011, п. 1; D-013, п. 5). */
enum class VolumeButtons {
    /** Будильник — «Отложить», таймер — «+1 мин». По умолчанию. */
    SNOOZE,

    /** Будильник — «Выключить», таймер — «Стоп». */
    DISMISS,

    /** Кнопки не трогают сигнал. */
    NOTHING,
}

/** Исход срабатывания для журнала (DATA_MODEL.md, п. 7). */
enum class SignalOutcome { DISMISSED, SNOOZED, AUTO_STOPPED, MISSED }

enum class SignalSource { ALARM, TIMER }
