package com.dpsmpx.dpclock.model

import java.time.LocalDate

/** Как часто сохранять копию по расписанию (D-036, п. 4). */
enum class AutoBackupPeriod(val days: Int) { OFF(0), DAILY(1), WEEKLY(7) }

/** Копия по расписанию: когда пора и какие старые файлы убрать. Чистые функции. */
object AutoBackupRules {
    /** В папке остаётся столько последних копий. */
    const val KEEP = 7
    private const val PREFIX = "dp-clock-auto-"
    private const val SUFFIX = ".json"
    private const val DAY_MILLIS = 24 * 3_600_000L

    /** Задание Android приходит не минута в минуту: час раньше срока — уже пора. */
    private const val SLACK_MILLIS = 3_600_000L

    /** Имя файла копии за [date]: по нему копии сортируются по дате. */
    fun fileName(date: LocalDate): String = "$PREFIX$date$SUFFIX"

    /** Файл — копия по расписанию; чужие файлы в папке не трогаются. */
    fun isOurs(name: String): Boolean = name.startsWith(PREFIX) && name.endsWith(SUFFIX)

    /** Пора сохранять: расписание включено и с прошлой копии [lastAt] (0 — не было) прошёл срок. */
    fun isDue(period: AutoBackupPeriod, lastAt: Long, now: Long): Boolean =
        period != AutoBackupPeriod.OFF && (lastAt <= 0L || now < lastAt || now - lastAt >= period.days * DAY_MILLIS - SLACK_MILLIS)

    /** Копии, которые пора удалить: все наши, кроме [keep] самых свежих по имени. */
    fun stale(names: Collection<String>, keep: Int = KEEP): List<String> =
        names.filter(::isOurs).sortedDescending().drop(keep)
}
