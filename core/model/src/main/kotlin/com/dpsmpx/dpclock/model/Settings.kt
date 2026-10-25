package com.dpsmpx.dpclock.model

/** Настройки (DATA_MODEL.md, п. 4; D-010, D-013). */
data class Settings(
    val snoozeMinutes: Int = DEFAULT_SNOOZE,
    /** Через сколько минут сигнал стихает сам; null — никогда. */
    val autoStopMinutes: Int? = DEFAULT_AUTO_STOP,
    val alarmOrder: AlarmOrder = AlarmOrder.TIME_OF_DAY,
    /** Показывать в строке включённых будильников время до следующего срабатывания. */
    val showAlarmCountdown: Boolean = false,
    /** Показывать в строке включённых будильников полную дату и время срабатывания (D-025, п. 2). */
    val showAlarmDate: Boolean = false,
    /** Человек отметил, что настроил автозапуск и батарею на Xiaomi (SCREEN_MAP.md, п. 10). */
    val xiaomiHintsDone: Boolean = false,
    val signalStyle: SignalStyle = SignalStyle.FULL_SCREEN,
    val volumeButtons: VolumeButtons = VolumeButtons.SNOOZE,
    /** Праздники и переносы производственного календаря России — в общем списке исключений (D-014, п. 3). */
    val holidaysRu: Boolean = false,
    /** Время сна и напоминание ложиться (D-017, п. 2). */
    val bedtime: Bedtime = Bedtime(),
    /** Погода вашего города в «Мировых часах» (D-019). */
    val weather: WeatherSettings = WeatherSettings(),
    /** Тема, цвет, стиль часов, фон, шум (D-021). */
    val appearance: Appearance = Appearance(),
    /** Предупреждения до сигнала у новых будильников, минуты (D-025, п. 5). */
    val defaultWarnings: List<Int> = emptyList(),
    /** Предупреждение со звуком; иначе — тихое уведомление. */
    val warningSound: Boolean = false,
    /** Куранты (D-025, п. 4). */
    val chimes: Chimes = Chimes(),
    /** Последние настройки помидора (D-025, п. 1). */
    val pomodoro: Pomodoro = Pomodoro(),
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

/**
 * Запись журнала срабатываний (DATA_MODEL.md, п. 7): когда сигнал ожидался
 * ([scheduledAt]), когда зазвучал ([firedAt]) и когда кончился ([endedAt] — с
 * 0.12.1, у записей раньше — null), мс UTC. [title] — название будильника или
 * таймера на тот момент: запись переживает их удаление.
 */
data class SignalEvent(
    val id: String,
    val source: SignalSource,
    val sourceId: String,
    val title: String,
    val scheduledAt: Long,
    val firedAt: Long,
    val outcome: SignalOutcome,
    val endedAt: Long? = null,
) {
    /** Опоздание сигнала, мс; null — сигнал не звучал (пропущен или выключен заранее). */
    val lateMillis: Long?
        get() = (firedAt - scheduledAt).takeIf { outcome != SignalOutcome.MISSED && it >= 0 }
}
