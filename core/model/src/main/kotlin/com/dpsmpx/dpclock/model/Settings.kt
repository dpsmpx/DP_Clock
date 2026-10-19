package com.dpsmpx.dpclock.model

/** Настройки MVP (DATA_MODEL.md, п. 4; D-010). */
data class Settings(
    val snoozeMinutes: Int = DEFAULT_SNOOZE,
    /** Через сколько минут сигнал стихает сам; null — никогда. */
    val autoStopMinutes: Int? = DEFAULT_AUTO_STOP,
    val alarmOrder: AlarmOrder = AlarmOrder.TIME_OF_DAY,
    /** Человек отметил, что настроил автозапуск и батарею на Xiaomi (SCREEN_MAP.md, п. 10). */
    val xiaomiHintsDone: Boolean = false,
) {
    companion object {
        const val DEFAULT_SNOOZE = 10
        const val DEFAULT_AUTO_STOP = 10
        val SNOOZE_CHOICES = listOf(1, 5, 10, 15, 20, 30)
        /** null — «никогда». */
        val AUTO_STOP_CHOICES: List<Int?> = listOf(1, 5, 10, 15, 20, 30, null)
    }
}

/** Исход срабатывания для журнала (DATA_MODEL.md, п. 7). */
enum class SignalOutcome { DISMISSED, SNOOZED, AUTO_STOPPED, MISSED }

enum class SignalSource { ALARM, TIMER }
