package com.dpsmpx.dpclock.data

import android.app.AlarmManager
import android.app.Application
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.model.Alarm
import com.dpsmpx.dpclock.model.Repeat
import com.dpsmpx.dpclock.model.SunAlarmRules
import com.dpsmpx.dpclock.model.SunEvent
import com.dpsmpx.dpclock.model.SunTime
import com.dpsmpx.dpclock.model.SunMoment
import com.dpsmpx.dpclock.model.SunRules
import com.dpsmpx.dpclock.model.SunSignal
import com.dpsmpx.dpclock.model.SunSignalKind
import com.dpsmpx.dpclock.model.SunSignals
import com.dpsmpx.dpclock.model.WeatherMode
import com.dpsmpx.dpclock.model.WeatherPlace
import com.dpsmpx.dpclock.model.WeatherSettings
import com.dpsmpx.dpclock.platform.AlarmReceiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant
import java.time.ZoneId

/**
 * Сигналы восхода и заката в базе и в будильниках Android (D-035): один будильник
 * Android на ближайший сигнал, место — геолокация, иначе город погоды; устаревший
 * будильник ничего не подаёт. Запуск — с `-Pscreenshots`: нужен образ Android.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SunRepositoryTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val graph get() = DpClockApp.graph(app)
    private val repository get() = graph.repository
    private val zone get() = ZoneId.systemDefault()
    private val tula = WeatherPlace("Тула", 54.1931, 37.6177)

    @Before
    fun setUp() {
        Assume.assumeTrue(System.getProperty("dpclock.robolectric") == "true")
        DpClockApp.resetGraph()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    /** Будильник Android сигналов восхода и заката; null — не поставлен. */
    @Suppress("DEPRECATION") // у ScheduledAlarm интент — только полем
    private fun scheduled(): ShadowAlarmManager.ScheduledAlarm? =
        shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .firstOrNull { shadowOf(it.operation).savedIntent.action == AlarmReceiver.ACTION_SUN }

    private fun expected(signals: SunSignals, latitude: Double, longitude: Double, after: Long = System.currentTimeMillis()) =
        SunRules.next(signals, latitude, longitude, Instant.ofEpochMilli(after), zone)

    @Test
    fun nearestSignalIsScheduledAndTheNextOneFollows() = runBlocking {
        val signals = SunSignals(sunrise = SunSignal(enabled = true), sunset = SunSignal(enabled = true))
        repository.updateSettings { it.copy(weather = WeatherSettings(WeatherMode.CITY, tula), sun = signals) }
        assertEquals(signals, repository.currentSettings().sun)

        // Без геолокации — город погоды.
        val first = expected(signals, tula.latitude, tula.longitude).first()
        val alarm = scheduled()
        assertNotNull(alarm)
        assertEquals(first.at.toEpochMilli(), alarm!!.triggerAtMs)
        // Одни уведомления — без значка будильника в строке состояния.
        assertNull(alarm.alarmClockInfo)

        val due = repository.onSunFired(first.at.toEpochMilli())
        assertEquals(listOf(first), due!!.fires)
        assertEquals(SunPlaceSource.WEATHER, due.place.source)
        assertEquals(5, first.minutes)
        // Следующий — в момент того же события.
        val second = scheduled()!!
        assertEquals(first.eventAt.toEpochMilli(), second.triggerAtMs)
    }

    @Test
    fun locationOfThePhoneWinsAndStaleAlarmsAreIgnored() = runBlocking {
        val signals = SunSignals(sunset = SunSignal(enabled = true, moments = listOf(SunMoment(0, SunSignalKind.ALARM))))
        repository.updateSettings { it.copy(weather = WeatherSettings(WeatherMode.CITY, tula), sun = signals) }
        val old = scheduled()!!.triggerAtMs

        // Телефон нашёлся во Владивостоке — закат там в другое время.
        assertTrue(graph.sun.setLocation("Владивосток", 43.1155, 131.8855, System.currentTimeMillis()))
        repository.syncSun()
        val fire = expected(signals, 43.1155, 131.8855).single()
        val alarm = scheduled()!!
        assertNotEquals(old, alarm.triggerAtMs)
        assertEquals(fire.at.toEpochMilli(), alarm.triggerAtMs)
        assertEquals(SunEvent.SUNSET, fire.event)
        // Будильник — как будильник: `setAlarmClock()`.
        assertNotNull(alarm.alarmClockInfo)

        // Будильник Android прежнего места уже ничего не значит.
        assertNull(repository.onSunFired(old))
        assertEquals(fire.at.toEpochMilli(), scheduled()!!.triggerAtMs)
        // Сигнал давно прошёл (телефон был выключен) — не подаётся.
        assertNull(repository.onSunFired(fire.at.toEpochMilli() - 3 * 24 * 3_600_000L))

        // Геолокацию запретили — снова город погоды.
        assertTrue(graph.sun.clearLocation())
        repository.syncSun()
        assertEquals(expected(signals, tula.latitude, tula.longitude).single().at.toEpochMilli(), scheduled()!!.triggerAtMs)

        // Выключили — будильника Android нет.
        repository.updateSettings { it.copy(sun = SunSignals()) }
        assertNull(scheduled())
    }

    /** Будильник со временем по солнцу (D-038): ставится на событие со сдвигом; час и минута — время срабатывания. */
    @Test
    fun alarmBySunIsScheduledForThePlaceAndMovesWithIt() = runBlocking {
        repository.updateSettings { it.copy(weather = WeatherSettings(WeatherMode.CITY, tula)) }
        val alarm = Alarm("s", 6, 0, Repeat.Once, enabled = true, createdAt = 1L, sun = SunTime(SunEvent.SUNRISE, -10))
        val at = repository.saveAlarm(alarm)
        val expected = SunAlarmRules.next(alarm, tula.latitude, tula.longitude, Instant.now(), zone)!!
        assertEquals(expected.toEpochMilli(), at)
        val stored = repository.surfaceData().alarms.single()
        assertEquals(expected.atZone(zone).hour, stored.hour)
        assertEquals(expected.atZone(zone).minute, stored.minute)
        assertEquals(alarm.sun, stored.sun)
        // В файл и синхронизацию идёт условное время: телефоны не перекидываются своими минутами.
        assertEquals(alarm, repository.exportBackup().let { com.dpsmpx.dpclock.model.BackupFormat.decode(com.dpsmpx.dpclock.model.BackupFormat.encode(it, Instant.now(), "t")) }.alarms.single())

        // Телефон нашёлся во Владивостоке — будильник переставлен на тамошний восход.
        graph.sun.setLocation("Владивосток", 43.1155, 131.8855, System.currentTimeMillis())
        repository.syncSun()
        val moved = SunAlarmRules.next(alarm, 43.1155, 131.8855, Instant.now(), zone)!!
        assertNotEquals(expected, moved)
        val after = repository.surfaceData().alarms.single()
        assertEquals(moved.atZone(zone).hour, after.hour)
        assertEquals(moved.atZone(zone).minute, after.minute)
        val scheduledAlarms = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.map { it.triggerAtMs }
        assertTrue(moved.toEpochMilli() in scheduledAlarms)
        assertTrue(expected.toEpochMilli() !in scheduledAlarms)
    }
}
