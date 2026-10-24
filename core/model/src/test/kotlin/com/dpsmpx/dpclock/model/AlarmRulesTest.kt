package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmRulesTest {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val newYork = ZoneId.of("America/New_York")

    private fun at(zone: ZoneId, text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun next(hour: Int, minute: Int, repeat: Repeat, zone: ZoneId, now: String): ZonedDateTime =
        AlarmRules.nextTrigger(hour, minute, repeat, at(zone, now), zone)!!.atZone(zone)

    @Test
    fun onceRingsTodayIfAheadOtherwiseTomorrow() {
        assertEquals(LocalDateTime.parse("2026-10-19T07:00"), next(7, 0, Repeat.Once, moscow, "2026-10-19T06:59").toLocalDateTime())
        assertEquals(LocalDateTime.parse("2026-10-20T07:00"), next(7, 0, Repeat.Once, moscow, "2026-10-19T07:00").toLocalDateTime())
        assertEquals(LocalDateTime.parse("2026-10-20T07:00"), next(7, 0, Repeat.Once, moscow, "2026-10-19T23:59").toLocalDateTime())
    }

    @Test
    fun timeIsStrictlyLaterThanNow() {
        val now = at(moscow, "2026-10-19T07:00")
        assertTrue(AlarmRules.nextTrigger(7, 0, Repeat.Once, now, moscow)!!.isAfter(now))
    }

    @Test
    fun weeklyPicksNextMatchingDayIncludingSameDayNextWeek() {
        // 19.10.2026 — понедельник.
        val mondays = Repeat.Weekly(setOf(DayOfWeek.MONDAY))
        assertEquals(LocalDateTime.parse("2026-10-19T08:00"), next(8, 0, mondays, moscow, "2026-10-19T07:00").toLocalDateTime())
        assertEquals(LocalDateTime.parse("2026-10-26T08:00"), next(8, 0, mondays, moscow, "2026-10-19T08:00").toLocalDateTime())
        val weekend = Repeat.Weekly(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        assertEquals(LocalDateTime.parse("2026-10-24T09:30"), next(9, 30, weekend, moscow, "2026-10-19T10:00").toLocalDateTime())
    }

    @Test
    fun crossesYearAndLeapDay() {
        assertEquals(LocalDateTime.parse("2027-01-01T00:00"), next(0, 0, Repeat.Once, moscow, "2026-12-31T23:59").toLocalDateTime())
        // 2028 — високосный: после 28 февраля идёт 29-е.
        assertEquals(LocalDateTime.parse("2028-02-29T06:00"), next(6, 0, Repeat.Once, moscow, "2028-02-28T06:00").toLocalDateTime())
        val tuesdays = Repeat.Weekly(setOf(DayOfWeek.TUESDAY))
        assertEquals(LocalDateTime.parse("2028-02-29T06:00"), next(6, 0, tuesdays, moscow, "2028-02-23T06:00").toLocalDateTime())
    }

    @Test
    fun springForwardGapRingsAtEndOfGap() {
        // Берлин, 29.03.2026: 2:00 → 3:00, времени 2:30 нет.
        val ring = next(2, 30, Repeat.Once, berlin, "2026-03-29T01:00")
        assertEquals(at(berlin, "2026-03-29T03:00"), ring.toInstant())
        assertEquals(3, ring.hour)
        assertEquals(0, ring.minute)
    }

    @Test
    fun fallBackOverlapRingsOnceAtFirstOccurrence() {
        // Нью-Йорк, 1.11.2026: 2:00 → 1:00, время 1:30 наступает дважды.
        val now = at(newYork, "2026-11-01T00:00")
        val first = AlarmRules.nextTrigger(1, 30, Repeat.Weekly(DayOfWeek.entries.toSet()), now, newYork)
        assertEquals(ZonedDateTime.ofLocal(LocalDateTime.parse("2026-11-01T01:30"), newYork, null).withEarlierOffsetAtOverlap().toInstant(), first)
        // Сразу после первого наступления следующий — уже завтра, а не через час.
        val after = AlarmRules.nextTrigger(1, 30, Repeat.Weekly(DayOfWeek.entries.toSet()), first!!, newYork)!!
        assertEquals(LocalDateTime.parse("2026-11-02T01:30"), after.atZone(newYork).toLocalDateTime())
        assertEquals(25 * 3_600_000L, after.toEpochMilli() - first.toEpochMilli())
    }

    @Test
    fun localTimeFollowsTimeZone() {
        val now = Instant.parse("2026-10-19T03:00:00Z")
        assertEquals(LocalDateTime.parse("2026-10-19T07:00"), AlarmRules.nextTrigger(7, 0, Repeat.Once, now, moscow)!!.atZone(moscow).toLocalDateTime())
        assertEquals(LocalDateTime.parse("2026-10-19T07:00"), AlarmRules.nextTrigger(7, 0, Repeat.Once, now, newYork)!!.atZone(newYork).toLocalDateTime())
    }

    @Test
    fun lateMoreThanTenMinutesIsMissed() {
        assertFalse(AlarmRules.isMissed(0L, 10 * 60_000L))
        assertTrue(AlarmRules.isMissed(0L, 10 * 60_000L + 1))
    }

    @Test
    fun sortsInThreeOrders() {
        val now = at(moscow, "2026-10-19T12:00")
        val a = Alarm("a", 13, 0, Repeat.Once, enabled = true, createdAt = 3)
        val b = Alarm("b", 7, 0, Repeat.Once, enabled = true, createdAt = 1)
        val c = Alarm("c", 6, 0, Repeat.Once, enabled = false, createdAt = 2)
        val all = listOf(a, b, c)
        assertEquals(listOf("c", "b", "a"), AlarmRules.sorted(all, AlarmOrder.TIME_OF_DAY, now, moscow).map { it.id })
        assertEquals(listOf("a", "b", "c"), AlarmRules.sorted(all, AlarmOrder.NEXT, now, moscow).map { it.id })
        assertEquals(listOf("b", "c", "a"), AlarmRules.sorted(all, AlarmOrder.CREATED, now, moscow).map { it.id })
    }

    @Test
    fun nearestIgnoresDisabled() {
        val now = at(moscow, "2026-10-19T12:00")
        val alarms = listOf(
            Alarm("a", 12, 30, Repeat.Once, enabled = false, createdAt = 0),
            Alarm("b", 13, 0, Repeat.Once, enabled = true, createdAt = 0),
        )
        assertEquals(at(moscow, "2026-10-19T13:00"), AlarmRules.nearest(alarms, now, moscow))
        assertEquals(null, AlarmRules.nearest(alarms.take(1), now, moscow))
    }

    @Test
    fun repeatBitsRoundTripAndKinds() {
        for (bits in 0 until 128) assertEquals(bits, Repeat.fromBits(bits).bits)
        assertEquals(Repeat.Once, Repeat.fromBits(0))
        assertEquals(RepeatKind.ONCE, Repeat.Once.kind)
        assertEquals(RepeatKind.DAILY, Repeat.fromBits(127).kind)
        assertEquals(RepeatKind.WEEKDAYS, Repeat.fromBits(0b0011111).kind)
        assertEquals(RepeatKind.DAYS, Repeat.fromBits(0b1100000).kind)
    }

    @Test
    fun countdownRoundsMinutesUp() {
        assertEquals(Countdown(0, 7, 20), Countdown.of((7 * 60 + 19) * 60_000L + 1))
        assertEquals(Countdown(0, 0, 1), Countdown.of(1))
        assertEquals(Countdown(1, 0, 0), Countdown.of(24 * 3_600_000L))
    }

    @Test
    fun onDateRingsOnceOnThatDateAndExpiresAfter() {
        val date = Repeat.OnDate(LocalDate.parse("2026-12-31"))
        assertEquals(LocalDateTime.parse("2026-12-31T23:00"), next(23, 0, date, moscow, "2026-10-19T12:00").toLocalDateTime())
        assertEquals(LocalDateTime.parse("2026-12-31T23:00"), next(23, 0, date, moscow, "2026-12-31T22:59").toLocalDateTime())
        assertEquals(null, AlarmRules.nextTrigger(23, 0, date, at(moscow, "2026-12-31T23:00"), moscow))
        assertEquals(null, AlarmRules.nextTrigger(23, 0, date, at(moscow, "2027-01-01T00:00"), moscow))
        assertEquals(RepeatKind.DATE, date.kind)
        assertFalse(date.isRepeating)
    }

    @Test
    fun skipDateSkipsOneOccurrenceOfARepeatingAlarm() {
        // 19.10.2026 — понедельник; пропуск понедельника 26-го — следующий понедельник, 2 ноября.
        val mondays = Alarm("a", 8, 0, Repeat.Weekly(setOf(DayOfWeek.MONDAY)), enabled = true, createdAt = 0)
        val now = at(moscow, "2026-10-19T09:00")
        val skipped = mondays.copy(skipDate = LocalDate.parse("2026-10-26"))
        assertEquals(at(moscow, "2026-10-26T08:00"), AlarmRules.nextTrigger(mondays, now, moscow))
        assertEquals(at(moscow, "2026-11-02T08:00"), AlarmRules.nextTrigger(skipped, now, moscow))
        assertEquals(at(moscow, "2026-10-26T08:00"), AlarmRules.nextIgnoringSkip(skipped, now, moscow))
        // Пропуск в прошлом ничего не меняет и считается устаревшим.
        val old = mondays.copy(skipDate = LocalDate.parse("2026-10-12"))
        assertEquals(at(moscow, "2026-10-26T08:00"), AlarmRules.nextTrigger(old, now, moscow))
        assertTrue(AlarmRules.isSkipStale(old, now, moscow))
        assertFalse(AlarmRules.isSkipStale(skipped, now, moscow))
    }

    @Test
    fun skipIsIgnoredForOneTimeAlarms() {
        val once = Alarm("a", 8, 0, Repeat.Once, enabled = true, createdAt = 0, skipDate = LocalDate.parse("2026-10-20"))
        assertEquals(at(moscow, "2026-10-20T08:00"), AlarmRules.nextTrigger(once, at(moscow, "2026-10-19T09:00"), moscow))
    }

    @Test
    fun expiredDateAlarmIsNotNearestAndSortsLast() {
        val now = at(moscow, "2026-10-19T12:00")
        val past = Alarm("p", 6, 0, Repeat.OnDate(LocalDate.parse("2026-10-01")), enabled = true, createdAt = 0)
        val soon = Alarm("s", 13, 0, Repeat.Once, enabled = true, createdAt = 1)
        assertEquals(at(moscow, "2026-10-19T13:00"), AlarmRules.nearest(listOf(past, soon), now, moscow))
        assertEquals(null, AlarmRules.nearest(listOf(past), now, moscow))
        assertEquals(listOf("s", "p"), AlarmRules.sorted(listOf(past, soon), AlarmOrder.NEXT, now, moscow).map { it.id })
    }

    @Test
    fun soundsRoundTripThroughTheDatabaseString() {
        val sounds = listOf(
            AlarmSound.Default,
            AlarmSound.Silent,
            AlarmSound.Builtin("chime"),
            AlarmSound.Ringtone("content://media/internal/audio/media/12", "Утро"),
            AlarmSound.File("content://com.android.providers.media.documents/document/audio%3A5", "песня.mp3"),
        )
        for (sound in sounds) assertEquals(sound, AlarmSound.decode(sound.encode(), sound.title))
        assertEquals(AlarmSound.Default, AlarmSound.decode("что-то новое", ""))
    }

    @Test
    fun silentWithoutVibrationIsUnnoticeable() {
        val alarm = Alarm("a", 8, 0, Repeat.Once, enabled = true, createdAt = 0, sound = AlarmSound.Silent)
        assertFalse(alarm.isUnnoticeable)
        assertTrue(alarm.copy(vibrate = false).isUnnoticeable)
        assertFalse(alarm.copy(sound = AlarmSound.Default, vibrate = false).isUnnoticeable)
    }
}
