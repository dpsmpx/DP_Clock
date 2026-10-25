package com.dpsmpx.dpclock.platform

import android.content.Context
import android.icu.text.DateFormat
import android.icu.text.DecimalFormatSymbols
import android.icu.util.Calendar
import android.icu.util.TimeZone
import com.dpsmpx.dpclock.model.Hms
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import android.text.format.DateFormat as SystemTimeFormat

/** Время и длительности строкой: язык и 12/24 часа — как в системе. */
object TimeText {
    private fun format(context: Context, withSeconds: Boolean = false): DateFormat {
        val is24 = SystemTimeFormat.is24HourFormat(context)
        val skeleton = (if (is24) "Hm" else "hm") + if (withSeconds) "s" else ""
        val locale = context.resources.configuration.locales[0]
        return DateFormat.getInstanceForSkeleton(skeleton, locale).apply {
            timeZone = TimeZone.getTimeZone(java.util.TimeZone.getDefault().id)
        }
    }

    /** Местное время будильника. */
    fun time(context: Context, hour: Int, minute: Int): String {
        val format = format(context)
        val calendar = Calendar.getInstance(format.timeZone).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return format.format(calendar.time)
    }

    /** Момент — местным временем. */
    fun time(context: Context, epochMillis: Long): String = format(context).format(Date(epochMillis))

    /** Момент — временем пояса [zoneId] (мировые часы, D-019). */
    fun time(context: Context, epochMillis: Long, zoneId: String): String =
        format(context).apply { timeZone = TimeZone.getTimeZone(zoneId) }.format(Date(epochMillis))

    /** Дата по языку системы: «пн, 27 окт.»; год — если не текущий или [withYear]. */
    fun date(context: Context, date: LocalDate, withYear: Boolean = date.year != LocalDate.now().year): String {
        val locale = context.resources.configuration.locales[0]
        val pattern = SystemTimeFormat.getBestDateTimePattern(locale, if (withYear) "EEEdMMMyyyy" else "EEEdMMM")
        return DateTimeFormatter.ofPattern(pattern, locale).format(date)
    }

    /** Дата коротко, без дня недели: «27 окт.»; год — если не текущий. */
    fun shortDate(context: Context, date: LocalDate): String {
        val locale = context.resources.configuration.locales[0]
        val pattern = SystemTimeFormat.getBestDateTimePattern(locale, if (date.year != LocalDate.now().year) "dMMMyyyy" else "dMMM")
        return DateTimeFormatter.ofPattern(pattern, locale).format(date)
    }

    /** Период дат: «1 янв. – 9 янв.»; одна дата — одна. */
    fun dateRange(context: Context, start: LocalDate, end: LocalDate): String =
        if (start == end) shortDate(context, start) else shortDate(context, start) + " – " + shortDate(context, end)

    /** Обратный отсчёт: 1:05:03 или 5:03. */
    fun countdown(millis: Long): String = hms(Hms.ofCountdown(millis))

    /** Длительность: 1:05:03 или 5:03. */
    fun duration(millis: Long): String = hms(Hms.of(millis))

    private fun hms(hms: Hms): String =
        if (hms.hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hms.hours, hms.minutes, hms.seconds)
        else String.format(Locale.ROOT, "%d:%02d", hms.minutes, hms.seconds)

    /** Секундомер: ЧЧ:ММ:СС,сс — разделитель долей по языку системы. */
    fun stopwatch(millis: Long, locale: Locale = Locale.getDefault()): String {
        val hms = Hms.of(millis)
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%02d", hms.hours, hms.minutes, hms.seconds, separator, hms.centis)
    }
}
