package com.dpsmpx.dpclock.data

import android.content.Context
import android.os.UserManager
import com.dpsmpx.dpclock.model.ActivityEvent
import com.dpsmpx.dpclock.model.ActivityKind
import com.dpsmpx.dpclock.model.Alarm
import com.dpsmpx.dpclock.model.AlarmGroup
import com.dpsmpx.dpclock.model.AlarmRules
import com.dpsmpx.dpclock.model.AlarmSound
import com.dpsmpx.dpclock.model.AlarmTemplate
import com.dpsmpx.dpclock.model.Backup
import com.dpsmpx.dpclock.model.BackupRules
import com.dpsmpx.dpclock.model.CalendarInstance
import com.dpsmpx.dpclock.model.CalendarRules
import com.dpsmpx.dpclock.model.CalendarWake
import com.dpsmpx.dpclock.model.ChimeRules
import com.dpsmpx.dpclock.model.ExceptionPeriod
import com.dpsmpx.dpclock.model.Exceptions
import com.dpsmpx.dpclock.model.Pomodoro
import com.dpsmpx.dpclock.model.Repeat
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.SignalEvent
import com.dpsmpx.dpclock.model.SleepRules
import com.dpsmpx.dpclock.model.SignalOutcome
import com.dpsmpx.dpclock.model.SignalSource
import com.dpsmpx.dpclock.model.StepSignal
import com.dpsmpx.dpclock.model.SyncChange
import com.dpsmpx.dpclock.model.SyncItems
import com.dpsmpx.dpclock.model.SyncResult
import com.dpsmpx.dpclock.model.Stopwatch
import com.dpsmpx.dpclock.model.StopwatchState
import com.dpsmpx.dpclock.model.SunFire
import com.dpsmpx.dpclock.model.SunRules
import com.dpsmpx.dpclock.model.SunSignalKind
import com.dpsmpx.dpclock.model.Task
import com.dpsmpx.dpclock.model.TaskHistory
import com.dpsmpx.dpclock.model.TaskMark
import com.dpsmpx.dpclock.model.TaskRepeat
import com.dpsmpx.dpclock.model.TaskRules
import com.dpsmpx.dpclock.model.TaskStats
import com.dpsmpx.dpclock.model.TaskTree
import com.dpsmpx.dpclock.model.TimerPreset
import com.dpsmpx.dpclock.model.TimerProgram
import com.dpsmpx.dpclock.model.TimerRules
import com.dpsmpx.dpclock.model.TimerRun
import com.dpsmpx.dpclock.model.TimerState
import com.dpsmpx.dpclock.model.Warnings
import com.dpsmpx.dpclock.model.isRepeating
import com.dpsmpx.dpclock.platform.Appearances
import com.dpsmpx.dpclock.platform.CalendarWrite
import com.dpsmpx.dpclock.platform.Clocks
import com.dpsmpx.dpclock.platform.FocusMode
import com.dpsmpx.dpclock.platform.Notifier
import com.dpsmpx.dpclock.platform.Ringing
import com.dpsmpx.dpclock.platform.Scheduler
import com.dpsmpx.dpclock.platform.Surfaces
import com.dpsmpx.dpclock.platform.TaskCalendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Состояние срабатывания (OccurrenceEntity.state). */
private object Occ {
    const val SCHEDULED = "SCHEDULED"
    const val RINGING = "RINGING"
    const val SNOOZED = "SNOOZED"

    /** Выключен; ждёт повторной проверки «Не спите?» в recheckAt (D-015, п. 4). */
    const val RECHECK = "RECHECK"
}

/** Что делать с пришедшим будильником. */
sealed interface AlarmFire {
    data class Ring(val alarm: Alarm, val scheduledAt: Long, val snoozeMinutes: Int) : AlarmFire

    /** Пора спросить «Не спите?». */
    data class Recheck(val alarm: Alarm) : AlarmFire
    data object Skip : AlarmFire
}

/** Снимок данных для виджетов и плиток (D-020). */
data class SurfaceData(
    val alarms: List<Alarm>,
    val exceptions: Exceptions,
    val presets: List<TimerPreset>,
    val runs: List<TimerRun>,
    val stopwatch: Stopwatch,
    val worldCities: List<String>,
    val settings: Settings,
    /** Свои задачи (D-032) — для виджета «Часы». */
    val tasks: List<Task> = emptyList(),
)

/**
 * Что делать с концом шага таймера; [endedAt] — расчётный конец шага, мс UTC, для журнала.
 * [TimerRun.isWaiting] у [run] — таймер ждёт «Дальше» (D-025, п. 1).
 */
data class TimerFire(val run: TimerRun, val signal: StepSignal, val sound: AlarmSound = AlarmSound.Default, val endedAt: Long? = null)

/** Пора подать сигналы восхода или заката (D-035) — для места [place]. */
data class SunDue(val fires: List<SunFire>, val place: SunPlace)

/** Пора предупредить о будильнике (D-025, п. 5): за [minutes] минут до [alarmAt], мс UTC. */
data class WarningFire(val alarm: Alarm, val alarmAt: Long, val minutes: Int)

/**
 * Все изменения данных и расписаний — здесь, по одному за раз: после каждого
 * изменения будильники Android и уведомления приводятся в соответствие с базой.
 */
class ClockRepository(
    private val context: Context,
    db: ClockDatabase,
    private val scheduler: Scheduler,
    private val notifier: Notifier,
    /** Календарь телефона (D-023): даты исключений и отменённые будильники перед событиями. */
    private val calendar: CalendarStore,
    /** Место для восхода и заката (D-035): последняя геолокация телефона. */
    private val sunStore: SunStore,
    /** Данные изменились — виджеты и плитки обновляются (D-020). */
    private val onChanged: () -> Unit = {},
) {
    private val dao = db.dao()
    private val mutex = Mutex()
    private val runtimeMarker = File(context.deviceStorage().noBackupFilesDir, "runtime-owner")
    private var ready = false

    /** Расписание будильников менялось в этом изменении — напоминание ложиться пересчитывается в конце. */
    private var alarmsChanged = false

    val alarms: Flow<List<Alarm>> = dao.alarms().map { list -> list.map { it.toModel() } }
    val settings: Flow<Settings> = dao.settings().map { it.toModel() }
    val presets: Flow<List<TimerPreset>> = dao.presets().map { list -> list.map { it.toModel() } }
    val timerRuns: Flow<List<TimerRun>> = dao.timerRuns().map { list -> list.map { it.toModel() } }
    val stopwatch: Flow<Stopwatch> = dao.stopwatch().map { it.toModel() }
    val groups: Flow<List<AlarmGroup>> = dao.groups().map { list -> list.map { it.toModel() } }
    val templates: Flow<List<AlarmTemplate>> = dao.templates().map { list -> list.map { it.toModel() } }

    /** Города мировых часов — пояса tz по порядку (D-019). */
    val worldCities: Flow<List<String>> = dao.worldCities()

    /** Свои задачи (D-032). */
    val tasks: Flow<List<Task>> = dao.tasks().map { list -> list.map { it.toModel() } }

    /** Отметки «сделано» (D-033), по времени. */
    val marks: Flow<List<TaskMark>> = dao.marks().map { list -> list.map { it.toModel() } }

    /** Журнал срабатываний, новые сверху. */
    val events: Flow<List<SignalEvent>> = dao.events().map { list -> list.map { it.toModel() } }

    /** Действия для статистики (D-023, п. 7), новые сверху. */
    val activities: Flow<List<ActivityEvent>> = dao.activities().map { list -> list.map { it.toModel() } }

    /** Общий список исключений: свои периоды, праздники, если включены (D-014, п. 4), и дни календаря телефона (D-023). */
    val exceptions: Flow<Exceptions> = combine(dao.exceptionsFlow(), settings, calendar.exceptionDates) { periods, settings, dates ->
        Exceptions(periods.map { it.toModel() }, settings.holidaysRu, dates)
    }

    /** Отложенные будильники: id → конец «Отложить». */
    val snoozed: Flow<Map<String, Long>> = dao.occurrencesFlow().map { list ->
        list.filter { it.state == Occ.SNOOZED && it.snoozedUntil != null }.associate { it.alarmId to it.snoozedUntil!! }
    }

    /** [quiet] — только чтение: виджеты и плитки не трогаются. */
    private suspend fun <T> locked(quiet: Boolean = false, block: suspend () -> T): T = mutex.withLock {
        if (!ready) {
            // Копия из резервной копии или с другого устройства: идущие таймеры,
            // секундомер и срабатывания относятся к тому устройству (DATA_MODEL.md, п. 8).
            if (!runtimeMarker.exists()) {
                dao.clearTimerRuns()
                dao.clearStopwatch()
                dao.clearOccurrences()
                runtimeMarker.parentFile?.mkdirs()
                runtimeMarker.createNewFile()
            }
            ready = true
        }
        val result = block()
        if (alarmsChanged) {
            alarmsChanged = false
            syncBedtimeLocked()
        }
        if (!quiet) onChanged()
        result
    }

    suspend fun currentSettings(): Settings = locked(quiet = true) { dao.currentSettings().toModel() }

    /** Всё, что показывают виджеты и плитки (D-020), одним чтением. */
    suspend fun surfaceData(): SurfaceData = locked(quiet = true) {
        val settings = dao.currentSettings().toModel()
        SurfaceData(
            alarms = dao.allAlarms().map { it.toModel() },
            exceptions = Exceptions(dao.allExceptions().map { it.toModel() }, settings.holidaysRu, calendar.exceptionDates.value),
            presets = dao.allPresets().map { it.toModel() },
            runs = dao.allTimerRuns().map { it.toModel() },
            stopwatch = dao.currentStopwatch().toModel(),
            worldCities = dao.allWorldCities().map { it.zoneId },
            settings = settings,
            tasks = dao.allTasks().map { it.toModel() },
        )
    }

    suspend fun updateSettings(change: (Settings) -> Settings) = locked {
        val old = dao.currentSettings().toModel()
        saveSettingsLocked(old, change(old))
    }

    private suspend fun saveSettingsLocked(old: Settings, new: Settings) {
        dao.upsertSettings(new.toEntity())
        // Праздники включили или выключили — у будильников другие даты.
        if (old.holidaysRu != new.holidaysRu) rescheduleAllLocked()
        if (old.bedtime != new.bedtime) alarmsChanged = true
        if (old.appearance != new.appearance) Appearances.apply(context, new.appearance)
        if (old.chimes != new.chimes) syncChimes(new)
        // Город погоды — запасное место для восхода и заката.
        if (old.sun != new.sun || old.weather != new.weather) syncSun(new)
    }

    // Будильники

    /**
     * Новый будильник — однократный и включённый (SCREEN_MAP.md, п. 1), можно сразу в группе,
     * с общими предупреждениями (D-025, п. 5). Возвращает момент срабатывания.
     */
    suspend fun addAlarm(
        hour: Int,
        minute: Int,
        groupId: String? = null,
        days: Set<DayOfWeek> = emptySet(),
        label: String = "",
    ): Long = locked {
        val warnings = dao.currentSettings().toModel().defaultWarnings
        val alarm = Alarm(
            UUID.randomUUID().toString(), hour, minute, Repeat.of(days), enabled = true, createdAt = now(),
            label = label, groupId = groupId, warnings = warnings,
        )
        dao.upsertAlarm(alarm.toEntity())
        scheduleAlarm(alarm)
    }

    /**
     * «Вздремнуть» (D-029, п. 3): однократный будильник через [minutes] минут, с точностью
     * до минуты — вверх. Будильник с названием [label] переиспользуется: список не копит их.
     */
    suspend fun nap(minutes: Int, label: String): Long = locked {
        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(now() + minutes * 60_000L + 59_999L).atZone(zone)
        val existing = dao.allAlarms().map { it.toModel() }
            .firstOrNull { it.label == label && it.repeat == Repeat.Once && it.groupId == null && it.calendarEventAt == null }
        if (existing != null && dao.occurrence(existing.id)?.state == Occ.RINGING) return@locked 0L
        val alarm = existing?.copy(hour = at.hour, minute = at.minute, enabled = true, skipDate = null, skipTime = null)
            ?: Alarm(UUID.randomUUID().toString(), at.hour, at.minute, Repeat.Once, enabled = true, createdAt = now(), label = label)
        dao.upsertAlarm(alarm.toEntity())
        resetOccurrence(alarm.id)
        scheduleAlarm(alarm)
    }

    /** Копия будильника (D-029, п. 4): тот же, но свой; без пропуска и не от календаря. Возвращает её id. */
    suspend fun copyAlarm(id: String): String? = locked {
        val alarm = dao.alarm(id)?.toModel() ?: return@locked null
        val copy = alarm.copy(id = UUID.randomUUID().toString(), createdAt = now(), skipDate = null, skipTime = null, calendarEventAt = null)
        dao.upsertAlarm(copy.toEntity())
        scheduleAlarm(copy)
        copy.id
    }

    /**
     * Сохранить изменённый будильник; прежнее срабатывание и «Отложить» отменяются.
     * Будильник перед событием после правки — обычный: календарь его больше не трогает (D-023, п. 3).
     */
    suspend fun saveAlarm(alarm: Alarm): Long = locked {
        calendarDateOf(alarm)?.let { calendar.setCancelled(it, cancelled = true) }
        val stored = normalized(alarm.copy(calendarEventAt = null))
        dao.upsertAlarm(stored.toEntity())
        resetOccurrence(stored.id)
        scheduleAlarm(stored)
    }

    // Группы и шаблоны (D-014, п. 5–6)

    suspend fun createGroup(name: String): String = locked {
        val group = AlarmGroup(UUID.randomUUID().toString(), name, now())
        dao.upsertGroup(group.toEntity())
        group.id
    }

    suspend fun renameGroup(id: String, name: String) = locked {
        val group = dao.group(id) ?: return@locked
        dao.upsertGroup(group.copy(name = name))
    }

    /** Удалить группу: будильники остаются без группы или удаляются с ней (кроме звонящего). */
    suspend fun deleteGroup(id: String, withAlarms: Boolean) = locked {
        if (withAlarms) {
            for (alarm in dao.alarmsInGroup(id)) {
                if (dao.occurrence(alarm.id)?.state == Occ.RINGING) continue
                dao.deleteAlarm(alarm.id)
                dao.deleteOccurrence(alarm.id)
                scheduler.cancelAlarm(alarm.id)
                notifier.cancelSnoozed(alarm.id)
                notifier.cancelWarning(alarm.id)
            }
            alarmsChanged = true
        }
        dao.ungroup(id)
        dao.deleteGroup(id)
    }

    /** Общее включение группы. Возвращает ближайшее срабатывание включённых (0 — нет). */
    suspend fun setGroupEnabled(id: String, enabled: Boolean): Long = locked {
        val exceptions = currentExceptions()
        dao.alarmsInGroup(id).map { it.toModel() }
            .filter { it.enabled != enabled }
            .map { alarm ->
                val updated = alarm.copy(enabled = enabled)
                dao.upsertAlarm(updated.toEntity())
                resetOccurrence(alarm.id)
                scheduleAlarm(updated, exceptions)
            }
            .filter { it > 0 }
            .minOrNull() ?: 0L
    }

    /** «Не звонить в исключения» — всем будильникам группы. */
    suspend fun setGroupHonorExceptions(id: String, honor: Boolean) = locked {
        val exceptions = currentExceptions()
        for (alarm in dao.alarmsInGroup(id).map { it.toModel() }) {
            val updated = alarm.copy(honorExceptions = honor)
            dao.upsertAlarm(updated.toEntity())
            resetOccurrence(alarm.id)
            scheduleAlarm(updated, exceptions)
        }
    }

    /** Шаблон из будильников группы. */
    suspend fun saveTemplate(groupId: String, name: String) = locked {
        val group = dao.group(groupId)?.toModel() ?: return@locked
        val alarms = dao.alarmsInGroup(groupId).map { it.toModel() }.sortedWith(compareBy({ it.minuteOfDay }, { it.createdAt }))
        if (alarms.isEmpty()) return@locked
        dao.upsertTemplate(AlarmTemplate.from(group, alarms, UUID.randomUUID().toString(), now(), name).toEntity())
    }

    /** Развернуть шаблон в новую группу с включёнными будильниками. Возвращает ближайшее срабатывание (0 — нет). */
    suspend fun applyTemplate(templateId: String): Long = locked {
        val template = dao.template(templateId)?.toModel() ?: return@locked 0L
        val now = now()
        val group = AlarmGroup(UUID.randomUUID().toString(), template.name, now)
        dao.upsertGroup(group.toEntity())
        val exceptions = currentExceptions()
        template.alarms.mapIndexed { index, blueprint ->
            // Порядок создания — порядок шаблона.
            val alarm = normalized(blueprint.copy(id = UUID.randomUUID().toString(), createdAt = now + index, groupId = group.id))
            dao.upsertAlarm(alarm.toEntity())
            scheduleAlarm(alarm, exceptions)
        }.filter { it > 0 }.minOrNull() ?: 0L
    }

    suspend fun deleteTemplate(id: String) = locked { dao.deleteTemplate(id) }

    // Исключения (D-014, п. 4)

    suspend fun saveException(period: ExceptionPeriod) = locked {
        dao.upsertException(period.toEntity())
        rescheduleAllLocked()
    }

    suspend fun deleteException(id: String) = locked {
        dao.deleteException(id)
        rescheduleAllLocked()
    }

    suspend fun setAlarmEnabled(id: String, enabled: Boolean) = locked {
        val alarm = dao.alarm(id)?.toModel() ?: return@locked
        val updated = alarm.copy(enabled = enabled)
        dao.upsertAlarm(updated.toEntity())
        resetOccurrence(id)
        scheduleAlarm(updated)
    }

    suspend fun deleteAlarm(id: String): Alarm? = locked {
        val alarm = dao.alarm(id)?.toModel() ?: return@locked null
        if (dao.occurrence(id)?.state == Occ.RINGING) return@locked null
        removeAlarmLocked(id)
        // Удалённый будильник перед событием календарь в эту дату не вернёт (D-023, п. 3).
        calendarDateOf(alarm)?.let { calendar.setCancelled(it, cancelled = true) }
        alarm
    }

    private suspend fun removeAlarmLocked(id: String) {
        dao.deleteAlarm(id)
        dao.deleteOccurrence(id)
        scheduler.cancelAlarm(id)
        notifier.cancelSnoozed(id)
        notifier.cancelWarning(id)
        alarmsChanged = true
    }

    /** «Отменить» после удаления. */
    suspend fun restoreAlarm(alarm: Alarm) = locked {
        calendarDateOf(alarm)?.let { calendar.setCancelled(it, cancelled = false) }
        dao.upsertAlarm(alarm.toEntity())
        scheduleAlarm(alarm)
    }

    // Календарь телефона (D-023)

    /**
     * Прочитанный календарь: [dates] — дни исключений, [events] — события
     * календарей событий на ближайшие дни. Будильники перед ранними событиями
     * приводятся к нему: новые ставятся, сдвинутые переставляются (включён ли —
     * решает человек), ненужные убираются. [label] — подпись будильника.
     */
    suspend fun syncCalendar(dates: Set<LocalDate>, events: List<CalendarInstance>, label: (CalendarWake) -> String) = locked {
        val datesChanged = calendar.setExceptionDates(dates)
        val zone = ZoneId.systemDefault()
        val nowInstant = Instant.ofEpochMilli(now())
        val today = nowInstant.atZone(zone).toLocalDate()
        calendar.pruneCancelled(today)
        val cancelled = calendar.cancelledDates()
        val alarms = dao.allAlarms().map { it.toModel() }
        val wakes = CalendarRules.earlyWakes(events, alarms, currentExceptions(), calendar.settings.value.earlyLeadMinutes, nowInstant, zone)
            .filter { it.date !in cancelled }
            .associateBy { it.date }
        val existing = alarms.filter { it.calendarEventAt != null }
        for (alarm in existing) {
            val date = calendarDateOf(alarm)
            val wake = date?.let { wakes[it] }
            val state = dao.occurrence(alarm.id)?.state
            // Звонит, отложен или ждёт «Не спите?» — до конца сигнала не трогать.
            if (state == Occ.RINGING || state == Occ.SNOOZED || state == Occ.RECHECK) continue
            if (wake == null) {
                dao.deleteAlarm(alarm.id)
                dao.deleteOccurrence(alarm.id)
                scheduler.cancelAlarm(alarm.id)
                notifier.cancelWarning(alarm.id)
                alarmsChanged = true
                continue
            }
            val updated = alarm.copy(hour = wake.wakeAt.hour, minute = wake.wakeAt.minute, calendarEventAt = wake.eventAt, label = label(wake))
            if (updated != alarm) {
                dao.upsertAlarm(updated.toEntity())
                resetOccurrence(alarm.id)
                scheduleAlarm(updated)
            }
        }
        val covered = existing.mapNotNull { calendarDateOf(it) }.toSet()
        for (wake in wakes.values.filter { it.date !in covered }) {
            val alarm = Alarm(
                UUID.randomUUID().toString(),
                wake.wakeAt.hour,
                wake.wakeAt.minute,
                Repeat.OnDate(wake.date),
                enabled = true,
                createdAt = now(),
                label = label(wake),
                calendarEventAt = wake.eventAt,
            )
            dao.upsertAlarm(alarm.toEntity())
            scheduleAlarm(alarm)
        }
        // Дни исключений изменились — у будильников другие даты.
        if (datesChanged) rescheduleAllLocked()
    }

    /** Дата будильника перед событием; у обычного — null. */
    private fun calendarDateOf(alarm: Alarm): LocalDate? =
        if (alarm.calendarEventAt == null) null else (alarm.repeat as? Repeat.OnDate)?.date

    /** Будильник Android пришёл: звонить, пропустить или ничего (устаревший). */
    suspend fun onAlarmFired(id: String, scheduledAt: Long): AlarmFire = locked {
        val alarm = dao.alarm(id)?.toModel() ?: return@locked AlarmFire.Skip
        val occ = dao.occurrence(id)
        val expected = when (occ?.state) {
            Occ.SCHEDULED -> occ.scheduledAt
            Occ.SNOOZED -> occ.snoozedUntil
            Occ.RECHECK -> occ.recheckAt
            else -> null
        }
        if (occ == null || expected != scheduledAt) {
            // Устаревший интент: расписание уже другое.
            if (occ?.state != Occ.RINGING) scheduleAlarm(alarm)
            return@locked AlarmFire.Skip
        }
        val now = now()
        if (occ.state == Occ.RECHECK) {
            // Телефон был выключен дольше 10 минут — проверять поздно, будильник идёт дальше.
            if (AlarmRules.isMissed(scheduledAt, now)) {
                dao.deleteOccurrence(id)
                scheduleAlarm(alarm)
                return@locked AlarmFire.Skip
            }
            return@locked AlarmFire.Recheck(alarm)
        }
        if (AlarmRules.isMissed(scheduledAt, now)) {
            missAlarm(alarm, occ.scheduledAt, now)
            return@locked AlarmFire.Skip
        }
        dao.upsertOccurrence(occ.copy(state = Occ.RINGING, ringingSince = now, snoozedUntil = null, expectedAt = scheduledAt))
        // Однократный и на дату выполнены — выключаются сразу, «Отложить» их не возвращает в список.
        if (!alarm.repeat.isRepeating) dao.upsertAlarm(alarm.copy(enabled = false).toEntity())
        notifier.cancelSnoozed(id)
        notifier.cancelWarning(id)
        AlarmFire.Ring(alarm, occ.scheduledAt, snoozeMinutes(alarm))
    }

    /** «Отложить»: возвращает конец интервала или null, если будильник не звонит. */
    suspend fun snoozeAlarm(id: String): Long? = locked {
        val occ = dao.occurrence(id)?.takeIf { it.state == Occ.RINGING } ?: return@locked null
        val now = now()
        val alarm = dao.alarm(id)?.toModel()
        val until = now + (alarm?.let { snoozeMinutes(it) } ?: dao.currentSettings().toModel().snoozeMinutes) * 60_000L
        dao.upsertOccurrence(occ.copy(state = Occ.SNOOZED, snoozedUntil = until, ringingSince = null))
        log(SignalSource.ALARM, id, occ.expectedAt ?: occ.scheduledAt, occ.ringingSince ?: now, SignalOutcome.SNOOZED, alarm?.label.orEmpty())
        scheduler.setAlarm(id, until)
        alarm?.let { notifier.snoozed(it, until) }
        until
    }

    /** «Выключить» — из сигнала или из уведомления об отложенном; автовыключение — тоже сюда. */
    suspend fun stopAlarm(id: String, outcome: SignalOutcome) = locked {
        val occ = dao.occurrence(id)
        val now = now()
        val recheck = dao.alarm(id)?.toModel()?.wakeCheck?.recheckMinutes ?: 0
        // В журнал — только звучавший сигнал: «Выключить» у отложенного уже записан как «Отложен».
        if (occ?.state == Occ.RINGING) {
            log(SignalSource.ALARM, id, occ.expectedAt ?: occ.scheduledAt, occ.ringingSince ?: now, outcome, dao.alarm(id)?.label.orEmpty())
        }
        if (outcome == SignalOutcome.DISMISSED && occ != null && occ.state != Occ.RECHECK) {
            dao.alarm(id)?.toModel()?.let { runAfterActions(it) }
        }
        if (outcome == SignalOutcome.DISMISSED && recheck > 0 && occ != null && occ.state != Occ.RECHECK) {
            // Выключен, но через несколько минут — «Не спите?» (D-011, п. 3; D-015, п. 4).
            val at = now + recheck * 60_000L
            dao.upsertOccurrence(occ.copy(state = Occ.RECHECK, snoozedUntil = null, ringingSince = null, recheckAt = at))
            notifier.cancelSnoozed(id)
            scheduler.setAlarm(id, at)
            alarmsChanged = true
            return@locked
        }
        dao.deleteOccurrence(id)
        scheduler.cancelAlarm(id)
        notifier.cancelSnoozed(id)
        val alarm = dao.alarm(id)?.toModel() ?: return@locked
        if (outcome == SignalOutcome.AUTO_STOPPED) notifier.missedAlarm(alarm, occ?.scheduledAt ?: now, autoStopped = true)
        scheduleAlarm(alarm)
    }

    /** Пришло время мягкого пробуждения (D-017, п. 3): будильник ещё ждёт этого срабатывания — его данные, иначе null. */
    suspend fun onGentleFired(id: String, alarmAt: Long): Alarm? = locked(quiet = true) {
        val occ = dao.occurrence(id)?.takeIf { it.state == Occ.SCHEDULED && it.scheduledAt == alarmAt } ?: return@locked null
        dao.alarm(id)?.toModel()?.takeIf { it.enabled && occ.scheduledAt > now() }
    }

    /**
     * «Я уже проснулся» во время мягкого пробуждения: это срабатывание не звонит.
     * Повторяющийся пропускает его дату, однократный и на дату выключаются.
     */
    suspend fun dismissEarly(id: String) = locked {
        val occ = dao.occurrence(id)?.takeIf { it.state == Occ.SCHEDULED } ?: return@locked
        val alarm = dao.alarm(id)?.toModel() ?: return@locked
        dao.deleteOccurrence(id)
        scheduler.cancelAlarm(id)
        notifier.cancelWarning(id)
        log(SignalSource.ALARM, id, occ.scheduledAt, now(), SignalOutcome.DISMISSED, alarm.label)
        runAfterActions(alarm)
        skipLocked(alarm, occ.scheduledAt)
    }

    /**
     * «Пропустить этот раз» из предупреждения (D-025, п. 5): ближайшее срабатывание
     * не звонит; повторяющийся идёт к следующему, однократный и на дату выключаются.
     */
    suspend fun skipOccurrence(id: String) = locked {
        val occ = dao.occurrence(id)?.takeIf { it.state == Occ.SCHEDULED } ?: return@locked
        val alarm = dao.alarm(id)?.toModel() ?: return@locked
        dao.deleteOccurrence(id)
        scheduler.cancelAlarm(id)
        notifier.cancelWarning(id)
        skipLocked(alarm, occ.scheduledAt)
    }

    private suspend fun skipLocked(alarm: Alarm, at: Long) {
        val next = if (alarm.repeat.isRepeating) {
            AlarmRules.skipping(alarm, Instant.ofEpochMilli(at), ZoneId.systemDefault())
        } else {
            alarm.copy(enabled = false)
        }
        dao.upsertAlarm(next.toEntity())
        scheduleAlarm(next)
    }

    /**
     * Пришло предупреждение (D-025, п. 5): будильник ещё ждёт срабатывания [alarmAt] —
     * что показать; следующее предупреждение о нём же ставится сразу. null — устарело.
     */
    suspend fun onWarningFired(id: String, alarmAt: Long): WarningFire? = locked(quiet = true) {
        val occ = dao.occurrence(id)?.takeIf { it.state == Occ.SCHEDULED && it.scheduledAt == alarmAt } ?: return@locked null
        val alarm = dao.alarm(id)?.toModel()?.takeIf { it.enabled } ?: return@locked null
        val now = now()
        if (occ.scheduledAt <= now) return@locked null
        // Сработавшее — ближайшее не в будущем: за столько минут и предупреждаем.
        val minutes = alarm.warnings.filter { occ.scheduledAt - it * 60_000L <= now + WARNING_SLACK_MILLIS }.minOrNull() ?: return@locked null
        scheduleWarning(alarm, occ.scheduledAt)
        WarningFire(alarm, occ.scheduledAt, minutes)
    }

    /** Ближайшее предупреждение о срабатывании [at]; прошли все — снять. */
    private fun scheduleWarning(alarm: Alarm, at: Long) {
        val next = Warnings.next(alarm.warnings, at, now())
        if (next == null) scheduler.cancelWarning(alarm.id) else scheduler.setWarning(alarm.id, next.first, at)
    }

    /** Напоминание ложиться — по настройкам и ближайшему срабатыванию (D-017, п. 2). */
    suspend fun syncBedtime() = locked { syncBedtimeLocked() }

    private suspend fun syncBedtimeLocked() {
        val bedtime = dao.currentSettings().toModel().bedtime
        // Есть включённые ключевые будильники — «Пора ложиться» считается по ним (D-037).
        val sleepAlarms = AlarmRules.forSleep(dao.allAlarms().map { it.toModel() }).mapTo(mutableSetOf()) { it.id }
        val nearest = dao.occurrences().filter { it.state == Occ.SCHEDULED && it.alarmId in sleepAlarms }.minOfOrNull { it.scheduledAt }
        val reminder = SleepRules.nextReminder(bedtime, nearest?.let(Instant::ofEpochMilli), Instant.ofEpochMilli(now()), ZoneId.systemDefault())
        if (reminder == null) {
            scheduler.cancelBedtime()
        } else {
            scheduler.setBedtime(reminder.remindAt.toEpochMilli(), reminder.bedAt.toEpochMilli(), reminder.alarmAt?.toEpochMilli())
        }
    }

    /** После выключения (D-017, п. 5): пресет таймера и напоминание; приложение открывает экран сигнала. */
    private suspend fun runAfterActions(alarm: Alarm) {
        val after = alarm.after
        after.presetId?.let { presetId ->
            val preset = dao.preset(presetId)?.toModel()
            if (preset != null) {
                val run = TimerRules.start(UUID.randomUUID().toString(), preset.id, preset.name, preset.program, Clocks.moment())
                dao.upsertTimerRun(run.toEntity())
                syncTimers()
            }
        }
        if (after.reminder.isNotBlank()) {
            if (after.reminderMinutes == 0) {
                notifier.reminder(alarm.id, after.reminder)
            } else {
                scheduler.setReminder(alarm.id, now() + after.reminderMinutes * 60_000L, after.reminder)
            }
        }
    }

    /** «Я не сплю» — проверка пройдена: будильник идёт к следующему срабатыванию. [shownAt] — когда вопрос появился. */
    suspend fun recheckPassed(id: String, shownAt: Long? = null) = locked {
        val occ = dao.occurrence(id)?.takeIf { it.state == Occ.RECHECK } ?: return@locked
        dao.deleteOccurrence(id)
        scheduler.cancelAlarm(id)
        val alarm = dao.alarm(id)?.toModel()
        alarm?.let { scheduleAlarm(it) }
        val now = now()
        log(SignalSource.ALARM, id, occ.recheckAt ?: now, shownAt ?: now, SignalOutcome.DISMISSED, alarm?.label.orEmpty())
    }

    /** На «Не спите?» не ответили: будильник звонит снова. null — звонить нечему. */
    suspend fun recheckFailed(id: String): AlarmFire.Ring? = locked {
        val occ = dao.occurrence(id)?.takeIf { it.state == Occ.RECHECK } ?: return@locked null
        val alarm = dao.alarm(id)?.toModel() ?: return@locked null
        val now = now()
        // Повторный сигнал — сразу после вопроса без ответа: ожидался сейчас.
        dao.upsertOccurrence(occ.copy(state = Occ.RINGING, ringingSince = now, recheckAt = null, expectedAt = now))
        AlarmFire.Ring(alarm, occ.scheduledAt, snoozeMinutes(alarm))
    }

    /**
     * Пересчитать все расписания (D-008, п. 5): запуск приложения, загрузка,
     * смена времени и пояса, обновление, выдача разрешения. Идемпотентно.
     */
    suspend fun rescheduleAll() = locked { rescheduleAllLocked() }

    private suspend fun rescheduleAllLocked() {
        val now = now()
        val exceptions = currentExceptions()
        val occurrences = dao.occurrences().associateBy { it.alarmId }
        for (alarm in dao.allAlarms().map { it.toModel() }) {
            val occ = occurrences[alarm.id]
            val due = when (occ?.state) {
                Occ.SCHEDULED -> occ.scheduledAt
                Occ.SNOOZED -> occ.snoozedUntil
                Occ.RINGING -> {
                    // Звонил, но процесс умер или телефон перезагрузился — сигнал потерян.
                    if (!Ringing.isAlarmRinging(alarm.id)) {
                        dao.deleteOccurrence(alarm.id)
                        missAlarm(alarm, occ.scheduledAt, now)
                    }
                    continue
                }
                Occ.RECHECK -> {
                    val at = occ.recheckAt ?: now
                    when {
                        Ringing.isRecheckShowing(alarm.id) -> Unit
                        // Проверка должна была быть давно — телефон был выключен; будильник идёт дальше.
                        AlarmRules.isMissed(at, now) -> {
                            dao.deleteOccurrence(alarm.id)
                            scheduleAlarm(alarm, exceptions)
                        }
                        else -> scheduler.setAlarm(alarm.id, at)
                    }
                    continue
                }
                else -> null
            }
            when {
                // Срок прошёл, пока будильники были отменены (принудительная остановка,
                // выключенный телефон): недавний — прозвонит сейчас, давний — пропуск.
                due != null && due <= now && occ != null ->
                    if (AlarmRules.isMissed(due, now)) {
                        dao.deleteOccurrence(alarm.id)
                        missAlarm(alarm, occ.scheduledAt, now)
                    } else {
                        scheduler.setAlarm(alarm.id, due)
                    }
                occ?.state == Occ.SNOOZED -> scheduler.setAlarm(alarm.id, due!!)
                else -> {
                    if (occ != null) dao.deleteOccurrence(alarm.id)
                    scheduleAlarm(alarm, exceptions)
                }
            }
        }
        syncTimers()
        notifier.stopwatch(dao.currentStopwatch().toModel())
        syncChimes(dao.currentSettings().toModel())
        syncSun(dao.currentSettings().toModel())
        rescheduleTasksLocked()
    }

    // Куранты (D-025, п. 4)

    /** Бой пришёл: следующий — по настройкам. */
    suspend fun onChimeFired() = locked(quiet = true) { syncChimes(dao.currentSettings().toModel()) }

    private fun syncChimes(settings: Settings) {
        val next = ChimeRules.next(settings.chimes, Instant.ofEpochMilli(now()), ZoneId.systemDefault())
        if (next == null) scheduler.cancelChime() else scheduler.setChime(next.toEpochMilli())
    }

    // Восход и закат (D-035)

    /**
     * Пришёл сигнал восхода или заката, поставленный на [at]: что подать и для какого
     * места; следующий ставится сразу. null — устарел: настройки или место сменились,
     * либо телефон был выключен и событие давно прошло.
     */
    suspend fun onSunFired(at: Long): SunDue? = locked(quiet = true) {
        val settings = dao.currentSettings().toModel()
        val now = now()
        val place = sunStore.place(settings)
        val fires = if (place == null || SunRules.isLate(at, now)) {
            emptyList()
        } else {
            SunRules.dueAt(settings.sun, place.latitude, place.longitude, Instant.ofEpochMilli(at), ZoneId.systemDefault())
        }
        // Неточный будильник (без разрешения) приходит и чуть раньше срока — этот же сигнал второй раз
        // не ставится. Устаревший будильник следующего не сдвигает.
        syncSun(settings, after = if (fires.isEmpty()) now else maxOf(now, at))
        if (place == null || fires.isEmpty()) null else SunDue(fires, place)
    }

    /** Место сменилось (геолокация обновилась или запрещена) — сигнал переставляется. */
    suspend fun syncSun() = locked(quiet = true) { syncSun(dao.currentSettings().toModel()) }

    private fun syncSun(settings: Settings, after: Long = now()) {
        val place = sunStore.place(settings)
        val fires = if (place == null) emptyList() else SunRules.next(settings.sun, place.latitude, place.longitude, Instant.ofEpochMilli(after), ZoneId.systemDefault())
        if (fires.isEmpty()) {
            scheduler.cancelSun()
        } else {
            scheduler.setSun(fires.first().at.toEpochMilli(), alarm = fires.any { it.kind == SunSignalKind.ALARM })
        }
    }

    private suspend fun currentExceptions(): Exceptions =
        Exceptions(dao.allExceptions().map { it.toModel() }, dao.currentSettings().toModel().holidaysRu, calendar.exceptionDates.value)

    /** У сменного графика и сложного повтора время будильника — самое раннее: по нему сортируется список. */
    private fun normalized(alarm: Alarm): Alarm = AlarmRules.normalized(alarm)

    /** Интервал «Отложить» будильника: свой или общий из настроек (D-013, п. 3). */
    private suspend fun snoozeMinutes(alarm: Alarm): Int =
        alarm.snoozeMinutes ?: dao.currentSettings().toModel().snoozeMinutes

    private suspend fun resetOccurrence(id: String) {
        notifier.cancelWarning(id)
        val occ = dao.occurrence(id) ?: return
        if (occ.state == Occ.RINGING) return
        dao.deleteOccurrence(id)
        notifier.cancelSnoozed(id)
    }

    /** Поставить ближайшее срабатывание; возвращает его момент (0 — будильник выключен или больше не сработает). */
    private suspend fun scheduleAlarm(stored: Alarm, knownExceptions: Exceptions? = null): Long {
        alarmsChanged = true
        val zone = ZoneId.systemDefault()
        val nowInstant = Instant.ofEpochMilli(now())
        var alarm = stored
        if (AlarmRules.isSkipStale(alarm, nowInstant, zone)) {
            alarm = alarm.copy(skipDate = null)
            dao.upsertAlarm(alarm.toEntity())
        }
        val occ = dao.occurrence(alarm.id)
        when (occ?.state) {
            Occ.RINGING -> return 0L
            Occ.SNOOZED -> {
                scheduler.cancelGentle(alarm.id)
                scheduler.setAlarm(alarm.id, occ.snoozedUntil!!)
                return occ.snoozedUntil
            }
            Occ.RECHECK -> {
                val at = occ.recheckAt ?: return 0L
                scheduler.setAlarm(alarm.id, at)
                return 0L
            }
        }
        if (!alarm.enabled) {
            dao.deleteOccurrence(alarm.id)
            scheduler.cancelAlarm(alarm.id)
            notifier.cancelWarning(alarm.id)
            return 0L
        }
        val at = AlarmRules.nextTrigger(alarm, nowInstant, zone, knownExceptions ?: currentExceptions())?.toEpochMilli()
        if (at == null) {
            // Дата прошла — будильник больше не сработает и выключается. У повторяющегося
            // все даты закрыли исключения: он остаётся включённым до их правки.
            if (!alarm.repeat.isRepeating) dao.upsertAlarm(alarm.copy(enabled = false).toEntity())
            dao.deleteOccurrence(alarm.id)
            scheduler.cancelAlarm(alarm.id)
            notifier.cancelWarning(alarm.id)
            return 0L
        }
        dao.upsertOccurrence(OccurrenceEntity(alarm.id, at, Occ.SCHEDULED, snoozedUntil = null, ringingSince = null, recheckAt = null))
        scheduler.setAlarm(alarm.id, at)
        scheduleWarning(alarm, at)
        // Мягкое пробуждение — только если до будильника ещё есть всё его время.
        val gentleAt = at - alarm.gentleMinutes * 60_000L
        if (alarm.gentleMinutes > 0 && gentleAt > nowInstant.toEpochMilli()) scheduler.setGentle(alarm.id, gentleAt, at) else scheduler.cancelGentle(alarm.id)
        return at
    }

    /** Пропуск (DATA_MODEL.md, п. 2): уведомление, однократный выключается, повторяющийся — к следующему. */
    private suspend fun missAlarm(alarm: Alarm, scheduledAt: Long, now: Long) {
        log(SignalSource.ALARM, alarm.id, scheduledAt, now, SignalOutcome.MISSED, alarm.label)
        notifier.missedAlarm(alarm, scheduledAt, autoStopped = false)
        val next = if (alarm.repeat.isRepeating) alarm else alarm.copy(enabled = false)
        dao.upsertAlarm(next.toEntity())
        dao.deleteOccurrence(alarm.id)
        scheduleAlarm(next)
    }

    // Перенос файлом (D-018)

    /** Всё, что переносится файлом. */
    suspend fun exportBackup(): Backup = locked(quiet = true) { backupLocked() }

    private suspend fun backupLocked(): Backup =
        Backup(
            // Будильники перед событиями — от календаря этого телефона, в файл не идут (D-023, п. 6).
            alarms = dao.allAlarms().map { it.toModel() }.filter { it.calendarEventAt == null }.sortedBy { it.createdAt },
            groups = dao.allGroups().map { it.toModel() },
            templates = dao.allTemplates().map { it.toModel() },
            exceptions = dao.allExceptions().map { it.toModel() },
            presets = dao.allPresets().map { it.toModel() },
            worldCities = dao.allWorldCities().map { it.zoneId },
            settings = dao.currentSettings().toModel(),
            tasks = dao.allTasks().map { it.toModel() },
            marks = dao.allMarks().map { it.toModel() },
        )

    /**
     * Загрузить файл (D-018, п. 4): добавить к имеющемуся или, при [replace],
     * сначала удалить будильники, группы, шаблоны, исключения и пресеты.
     * Звонящий сейчас будильник остаётся. Настройки — из файла, если [withSettings].
     */
    suspend fun importBackup(backup: Backup, replace: Boolean, withSettings: Boolean) = locked {
        if (replace) clearLocked(keepCalendar = false)
        val prepared = BackupRules.forImport(backup) { UUID.randomUUID().toString() }
        prepared.groups.forEach { dao.upsertGroup(it.toEntity()) }
        prepared.templates.forEach { dao.upsertTemplate(it.toEntity()) }
        prepared.exceptions.forEach { dao.upsertException(it.toEntity()) }
        prepared.presets.forEach { dao.upsertPreset(it.toEntity()) }
        prepared.alarms.forEach { dao.upsertAlarm(normalized(it).toEntity()) }
        prepared.marks.forEach { dao.upsertMark(it.toEntity()) }
        prepared.tasks.forEach { saveTaskLocked(it) }
        // Города — пояса: тот же город второй раз не добавляется.
        val cities = dao.allWorldCities().map { it.zoneId }.toMutableList()
        for (zoneId in prepared.worldCities.filter { it !in cities }) {
            dao.upsertWorldCity(WorldCityEntity(zoneId, cities.size, now()))
            cities += zoneId
        }
        val settings = prepared.settings
        if (withSettings && settings != null) {
            // Отметки про Xiaomi и первый запуск — о настройке этого телефона, их файл не меняет.
            val phone = dao.currentSettings().toModel()
            dao.upsertSettings(settings.copy(xiaomiHintsDone = phone.xiaomiHintsDone, onboardingDone = phone.onboardingDone).toEntity())
            Appearances.apply(context, settings.appearance)
        }
        alarmsChanged = true
        rescheduleAllLocked()
    }

    /** Удалить будильники, группы, шаблоны, исключения, пресеты и города; звонящий будильник остаётся без группы. */
    private suspend fun clearLocked(keepCalendar: Boolean) {
        for (alarm in dao.allAlarms()) {
            if (keepCalendar && alarm.calendarEventAt != null) continue
            if (dao.occurrence(alarm.id)?.state == Occ.RINGING) {
                dao.upsertAlarm(alarm.copy(groupId = null))
                continue
            }
            removeAlarmLocked(alarm.id)
        }
        dao.clearGroups()
        dao.clearTemplates()
        dao.clearExceptions()
        dao.clearPresets()
        dao.clearWorldCities()
        for (task in dao.allTasks()) removeTaskLocked(task.id)
    }

    // Свои задачи (D-032)

    suspend fun task(id: String): Task? = locked(quiet = true) { dao.task(id)?.toModel() }

    suspend fun currentTasks(): List<Task> = locked(quiet = true) { dao.allTasks().map { it.toModel() } }

    /**
     * Сохранить задачу из редактора: название без пробелов по краям, повтор месяца и
     * года — от числа срока, без срока — без времени и повтора. Потом — напоминание и
     * событие календаря по флажку.
     */
    suspend fun saveTask(task: Task) = locked {
        val date = task.date
        saveTaskLocked(
            task.copy(
                title = task.title.trim().take(Task.MAX_TITLE),
                note = task.note.trimEnd().take(Task.MAX_NOTE),
                time = if (date == null) null else task.time,
                repeat = if (date == null) TaskRepeat.None else TaskRepeat.of(task.repeat, date),
                createdAt = task.createdAt.takeIf { it > 0 } ?: now(),
            ),
        )
    }

    /** «Сделано» (D-032): повторяющаяся — на следующий срок, прежнее событие календаря — с «✓». */
    suspend fun completeTask(id: String): TaskMark? = locked {
        val task = dao.task(id)?.toModel()?.takeIf { !it.done } ?: return@locked null
        val now = now()
        // Каждое «сделано» — отметка (D-033): у постоянной — только она, задача остаётся.
        val mark = TaskMark(UUID.randomUUID().toString(), id, now)
        dao.upsertMark(mark.toEntity())
        val next = TaskRules.complete(task, LocalDate.now(), now)
        if (!task.permanent && !next.done && task.eventId != null) withCalendar(task.copy(done = true))
        saveTaskLocked(next)
        notifier.cancelTaskReminder(id)
        mark
    }

    /** Отметка задним числом (D-033): «забыл отметить вовремя». */
    suspend fun addMark(taskId: String, at: Long) = locked {
        val task = dao.task(taskId)?.toModel() ?: return@locked
        dao.upsertMark(TaskMark(UUID.randomUUID().toString(), taskId, at).toEntity())
        scheduleNudge(task)
    }

    /** Удалить ошибочную отметку; у обычной задачи «сделано» это не отменяет. */
    suspend fun deleteMark(id: String) = locked {
        val mark = dao.mark(id) ?: return@locked
        dao.deleteMark(id)
        dao.task(mark.taskId)?.toModel()?.let { scheduleNudge(it) }
    }

    /**
     * Пришло время тихого напоминания (D-033): задача и её история, если оно ещё
     * нужно. Напоминание помечается — для этой отметки второго не будет.
     */
    suspend fun onTaskNudge(id: String): Pair<Task, TaskStats>? = locked(quiet = true) {
        val task = dao.task(id)?.toModel() ?: return@locked null
        val marks = dao.marksOf(id).map { it.at }
        val at = TaskHistory.nudgeAt(task, marks, Instant.ofEpochMilli(now()), ZoneId.systemDefault()) ?: return@locked null
        // Поставлено раньше срока (сдвинули часы) — подождать.
        if (at.toEpochMilli() > now() + NUDGE_SLACK_MILLIS) {
            scheduler.setTaskNudge(id, at.toEpochMilli())
            return@locked null
        }
        dao.upsertTask(task.copy(nudged = TaskHistory.base(task, marks)).toEntity())
        task to TaskHistory.stats(marks)
    }

    /** Вернуть сделанную задачу в работу. */
    suspend fun reopenTask(id: String) = locked {
        val task = dao.task(id)?.toModel()?.takeIf { it.done } ?: return@locked
        saveTaskLocked(task.copy(done = false, doneAt = null))
    }

    /** Удалить задачу с подзадачами, событиями и отметками; возвращает удалённую. */
    suspend fun deleteTask(id: String): Task? = locked {
        val task = dao.task(id)?.toModel() ?: return@locked null
        val all = dao.allTasks().map { it.toModel() }
        for (child in TaskTree.descendants(all, id)) removeTaskLocked(child)
        removeTaskLocked(id)
        task.copy(eventId = null)
    }

    private suspend fun saveTaskLocked(task: Task) {
        val stored = withCalendar(task)
        dao.upsertTask(stored.toEntity())
        scheduleTaskReminder(stored)
        scheduleNudge(stored)
    }

    private suspend fun removeTaskLocked(id: String) {
        dao.task(id)?.eventId?.let { TaskCalendar.delete(context, it) }
        dao.deleteTask(id)
        dao.deleteMarksOf(id)
        scheduler.cancelTaskReminder(id)
        scheduler.cancelTaskNudge(id)
        notifier.cancelTaskReminder(id)
    }

    /** Тихое напоминание постоянной задачи (D-033) — по её отметкам. */
    private suspend fun scheduleNudge(task: Task) {
        val at = TaskHistory.nudgeAt(task, dao.marksOf(task.id).map { it.at }, Instant.ofEpochMilli(now()), ZoneId.systemDefault())
        if (at != null) scheduler.setTaskNudge(task.id, at.toEpochMilli()) else scheduler.cancelTaskNudge(task.id)
    }

    /**
     * Событие календаря по флажку «В календарь» (D-032, п. 6): записать, поправить или
     * удалить. Событие удалили в календаре — флажок снимается. Не вышло (нет разрешения,
     * календарь не выбран, телефон ещё не разблокирован) — попробует [rescheduleAll].
     */
    private fun withCalendar(task: Task): Task {
        val eventId = task.eventId
        if (!task.inCalendar || task.date == null) {
            if (eventId != null) TaskCalendar.delete(context, eventId)
            return task.copy(eventId = null)
        }
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return task
        val calendarId = calendar.settings.value.taskCalendar ?: return task
        return when (val write = TaskCalendar.write(context, calendarId, task)) {
            is CalendarWrite.Written -> task.copy(eventId = write.eventId)
            CalendarWrite.Gone -> task.copy(inCalendar = false, eventId = null)
            CalendarWrite.Failed -> task
        }
    }

    private fun scheduleTaskReminder(task: Task) {
        val at = TaskRules.reminderAt(task, ZoneId.systemDefault(), Instant.ofEpochMilli(now()))
        if (at != null) scheduler.setTaskReminder(task.id, at.toEpochMilli()) else scheduler.cancelTaskReminder(task.id)
    }

    /** Напоминания всех задач и события, которые не записались раньше. */
    private suspend fun rescheduleTasksLocked() {
        for (task in dao.allTasks().map { it.toModel() }) {
            scheduleTaskReminder(task)
            scheduleNudge(task)
            if (task.inCalendar && task.eventId == null && task.date != null) {
                val fixed = withCalendar(task)
                if (fixed != task) dao.upsertTask(fixed.toEntity())
            }
        }
    }

    // Синхронизация (D-030)

    /**
     * Проход синхронизации под общим замком, чтобы правка человека не потерялась между
     * снимком и применением: снимок данных, слияние [merge] (чистая функция ядра) и
     * применение пришедшего. Звонящий будильник и ждущий проверки «Не спите?» —
     * в [merge] вторым аргументом: их ждёт следующий проход. При [replace] — первая
     * синхронизация «Взять данные из файла»: свои будильники, группы, шаблоны,
     * исключения, пресеты и города удаляются, настройки берутся из файла.
     */
    suspend fun sync(replace: Boolean, merge: (Map<String, Map<String, Any?>>, Set<String>) -> SyncResult): SyncResult = locked(quiet = true) {
        val busy = dao.occurrences().filter { it.state == Occ.RINGING || it.state == Occ.RECHECK }.map { SyncItems.ALARM + it.alarmId }.toSet()
        if (replace) clearLocked(keepCalendar = true)
        val snapshot = SyncItems.snapshot(backupLocked())
        val result = merge(if (replace) snapshot - SyncItems.SETTINGS - SyncItems.WORLD_CITIES else snapshot, busy)
        if (result.changes.isNotEmpty() || replace) {
            applySyncLocked(result.changes)
            rescheduleAllLocked()
            alarmsChanged = true
            onChanged()
        }
        result
    }

    /**
     * Пришедшее с другого телефона — в базу. Включение и пропуск будильника — свои у
     * телефона: новый будильник приходит выключенным. Отметки о настройке телефона и
     * своя картинка фона не меняются. Сначала группы, потом будильники в них; удалённая
     * группа — последней, её будильники остаются без группы.
     */
    private suspend fun applySyncLocked(changes: List<SyncChange>) {
        val order = listOf(
            SyncItems.GROUP,
            SyncItems.TEMPLATE,
            SyncItems.EXCEPTION,
            SyncItems.PRESET,
            SyncItems.ALARM,
            SyncItems.TASK,
            SyncItems.MARK,
            SyncItems.SETTINGS,
            SyncItems.WORLD_CITIES,
        )
        val sorted = changes.sortedWith(
            compareBy<SyncChange>({ it is SyncChange.Remove && it.key.startsWith(SyncItems.GROUP) }, { change -> order.indexOfFirst { change.key.startsWith(it) } }),
        )
        for (change in sorted) {
            val key = change.key
            val id = SyncItems.id(key)
            when (change) {
                is SyncChange.Put -> {
                    val data = change.data
                    when {
                        key.startsWith(SyncItems.GROUP) -> dao.upsertGroup(SyncItems.group(data).toEntity())
                        key.startsWith(SyncItems.TEMPLATE) -> dao.upsertTemplate(SyncItems.template(data).toEntity())
                        key.startsWith(SyncItems.EXCEPTION) -> dao.upsertException(SyncItems.exception(data).toEntity())
                        key.startsWith(SyncItems.PRESET) -> dao.upsertPreset(SyncItems.preset(data).toEntity())
                        key.startsWith(SyncItems.TASK) -> {
                            // Флажок «В календарь» и событие — свои у телефона (D-032).
                            val incoming = SyncItems.task(data)
                            val phone = dao.task(incoming.id)?.toModel()
                            saveTaskLocked(incoming.copy(inCalendar = phone?.inCalendar ?: false, eventId = phone?.eventId, nudged = phone?.nudged))
                        }
                        key.startsWith(SyncItems.MARK) -> {
                            val mark = SyncItems.mark(data)
                            dao.upsertMark(mark.toEntity())
                            dao.task(mark.taskId)?.toModel()?.let { scheduleNudge(it) }
                        }
                        key.startsWith(SyncItems.ALARM) -> {
                            val incoming = SyncItems.alarm(data)
                            val phone = dao.alarm(incoming.id)?.toModel()
                            val alarm = incoming.copy(
                                enabled = phone?.enabled ?: false,
                                skipDate = phone?.skipDate,
                                skipTime = phone?.skipTime,
                                calendarEventAt = null,
                            )
                            dao.upsertAlarm(normalized(alarm).toEntity())
                            resetOccurrence(alarm.id)
                        }
                        key == SyncItems.SETTINGS -> {
                            val old = dao.currentSettings().toModel()
                            val new = SyncItems.settings(data).copy(xiaomiHintsDone = old.xiaomiHintsDone, onboardingDone = old.onboardingDone)
                            saveSettingsLocked(old, new)
                        }
                        key == SyncItems.WORLD_CITIES -> {
                            dao.clearWorldCities()
                            SyncItems.worldCities(data).forEachIndexed { index, zoneId -> dao.upsertWorldCity(WorldCityEntity(zoneId, index, now())) }
                        }
                    }
                }
                is SyncChange.Remove -> when {
                    key.startsWith(SyncItems.GROUP) -> {
                        dao.ungroup(id)
                        dao.deleteGroup(id)
                    }
                    key.startsWith(SyncItems.TEMPLATE) -> dao.deleteTemplate(id)
                    key.startsWith(SyncItems.EXCEPTION) -> dao.deleteException(id)
                    key.startsWith(SyncItems.PRESET) -> dao.deletePreset(id)
                    key.startsWith(SyncItems.TASK) -> removeTaskLocked(id)
                    key.startsWith(SyncItems.MARK) -> dao.deleteMark(id)
                    key.startsWith(SyncItems.ALARM) -> if (dao.alarm(id) != null) removeAlarmLocked(id)
                }
            }
        }
    }

    // Мировые часы (D-019)

    /** Город в конец списка; уже есть — ничего. */
    suspend fun addWorldCity(zoneId: String) = locked {
        val cities = dao.allWorldCities()
        if (cities.any { it.zoneId == zoneId }) return@locked
        dao.upsertWorldCity(WorldCityEntity(zoneId, (cities.maxOfOrNull { it.position } ?: -1) + 1, now()))
    }

    suspend fun removeWorldCity(zoneId: String) = locked { dao.deleteWorldCity(zoneId) }

    /** Сдвинуть город на одно место вверх (−1) или вниз (+1). */
    suspend fun moveWorldCity(zoneId: String, step: Int) = locked {
        val cities = dao.allWorldCities().toMutableList()
        val from = cities.indexOfFirst { it.zoneId == zoneId }.takeIf { it >= 0 } ?: return@locked
        val to = (from + step).coerceIn(0, cities.lastIndex)
        cities.add(to, cities.removeAt(from))
        cities.forEachIndexed { index, city -> dao.upsertWorldCity(city.copy(position = index)) }
    }

    // Таймеры

    suspend fun startTimer(program: TimerProgram, name: String, presetId: String?) = locked {
        val run = TimerRules.start(UUID.randomUUID().toString(), presetId, name, program, Clocks.moment())
        dao.upsertTimerRun(run.toEntity())
        syncTimers()
        // Таймер засыпания — «лёг» для статистики сна (D-023, п. 7).
        if (program.sleep) logActivity(ActivityKind.SLEEP_TIMER, now(), now(), amountMillis = program.totalMillis)
        // Для плиток (D-020): длина таймера засыпания и последний обычный таймер.
        Surfaces.rememberTimer(context, program, name, presetId)
    }

    /** Пресет одним нажатием — из виджета (D-020). Пресета нет — false. */
    suspend fun startPreset(id: String): Boolean = locked {
        val preset = dao.preset(id)?.toModel() ?: return@locked false
        val run = TimerRules.start(UUID.randomUUID().toString(), preset.id, preset.name, preset.program, Clocks.moment())
        dao.upsertTimerRun(run.toEntity())
        syncTimers()
        Surfaces.rememberTimer(context, preset.program, preset.name, preset.id)
        true
    }

    /** Остановить и убрать таймер засыпания — плитка (D-020). */
    suspend fun stopSleepTimers() = locked {
        for (run in dao.allTimerRuns().map { it.toModel() }.filter { it.program.sleep }) {
            logTimer(run, completed = false)
            dao.deleteTimerRun(run.id)
            scheduler.cancelTimer(run.id)
            notifier.cancelTimer(run.id)
        }
    }

    /** Помидор (D-025, п. 1): настройки запоминаются, таймер идёт с первой работы. */
    suspend fun startPomodoro(pomodoro: Pomodoro, program: TimerProgram, name: String) = locked {
        dao.upsertSettings(dao.currentSettings().toModel().copy(pomodoro = pomodoro).toEntity())
        val run = TimerRules.start(UUID.randomUUID().toString(), null, name, program, Clocks.moment())
        dao.upsertTimerRun(run.toEntity())
        syncTimers()
    }

    /** «Дальше»: следующий шаг сейчас; после последнего — таймер закончен и убран (D-025, п. 1). */
    suspend fun skipStep(id: String) = locked {
        val run = dao.timerRun(id)?.toModel() ?: return@locked
        val next = TimerRules.skip(run, Clocks.moment())
        if (next == null) {
            logTimer(run, completed = false)
            dao.deleteTimerRun(id)
            scheduler.cancelTimer(id)
            notifier.cancelTimer(id)
        } else {
            dao.upsertTimerRun(next.toEntity())
        }
        syncTimers()
    }

    suspend fun pauseTimer(id: String) = updateTimer(id) { TimerRules.pause(it, Clocks.moment()) }

    suspend fun resumeTimer(id: String) = updateTimer(id) { TimerRules.resume(it, Clocks.moment()) }

    suspend fun resetTimer(id: String) = updateTimer(id) { TimerRules.reset(it) }

    suspend fun addMinute(id: String) = updateTimer(id) { TimerRules.addMinute(it, Clocks.moment()) }

    private suspend fun updateTimer(id: String, change: (TimerRun) -> TimerRun) = locked {
        val run = dao.timerRun(id)?.toModel() ?: return@locked
        dao.upsertTimerRun(change(run).toEntity())
        syncTimers()
    }

    suspend fun deleteTimer(id: String) = locked {
        dao.timerRun(id)?.toModel()?.let { logTimer(it, completed = false) }
        dao.deleteTimerRun(id)
        scheduler.cancelTimer(id)
        notifier.cancelTimer(id)
        syncTimers()
    }

    /**
     * Звонящий таймер выключен: закончившийся удаляется, промежуточный шаг идёт дальше.
     * [endedAt] — расчётный конец шага, [ringingSince] — начало сигнала, мс UTC; для журнала.
     */
    suspend fun stopTimerSignal(id: String, outcome: SignalOutcome, endedAt: Long? = null, ringingSince: Long? = null) = locked {
        val run = dao.timerRun(id)?.toModel() ?: return@locked
        val now = now()
        log(SignalSource.TIMER, id, endedAt ?: now, ringingSince ?: now, outcome, run.name)
        if (run.state == TimerState.RINGING) {
            logTimer(run, completed = true)
            dao.deleteTimerRun(id)
            scheduler.cancelTimer(id)
            notifier.cancelTimer(id)
            if (outcome == SignalOutcome.AUTO_STOPPED) notifier.timerFinished(run)
        }
    }

    /** Будильник Android на конец шага пришёл. */
    suspend fun onTimerStepEnded(id: String): TimerFire? = locked {
        val run = dao.timerRun(id)?.toModel()?.takeIf { it.state == TimerState.RUNNING } ?: return@locked null
        val now = Clocks.moment()
        val result = TimerRules.advance(run, now)
        var signal = if (result.late) StepSignal.NONE else result.signal
        // Конец шага по монотонным часам — перевод времени не искажает опоздание в журнале.
        val endedAt = result.endedAt?.let { now.utc + it.millisAfter(now) }
        // Помидоры — в статистику (D-025, п. 1); закончившиеся, пока телефон был выключен, не в счёт.
        if (result.focusEnded > 0 && !result.late) {
            val end = endedAt ?: now.utc
            logActivity(ActivityKind.POMODORO, end - result.focusMillis, end, run.name, amountMillis = result.focusMillis, count = result.focusEnded)
        }
        if (result.run.state == TimerState.RINGING && run.program.sleep) {
            // Таймер засыпания закончился: без сигнала, музыку останавливает приёмник (D-016, п. 1).
            logTimer(run, completed = true)
            dao.deleteTimerRun(id)
            scheduler.cancelTimer(id)
            notifier.cancelTimer(id)
            log(SignalSource.TIMER, id, endedAt ?: now.utc, now.utc, if (result.late) SignalOutcome.MISSED else SignalOutcome.DISMISSED, run.name)
        } else if (result.run.state == TimerState.RINGING && result.late) {
            // Закончился, пока телефон был выключен: только уведомление (DATA_MODEL.md, п. 5).
            logTimer(run, completed = true)
            dao.deleteTimerRun(id)
            scheduler.cancelTimer(id)
            notifier.cancelTimer(id)
            notifier.timerFinished(run)
            log(SignalSource.TIMER, id, endedAt ?: now.utc, now.utc, SignalOutcome.MISSED, run.name)
            signal = StepSignal.NONE
        } else {
            dao.upsertTimerRun(result.run.toEntity())
        }
        syncTimers()
        TimerFire(result.run, signal, result.sound, endedAt)
    }

    /** Будильники Android и уведомления — по идущим таймерам. */
    private suspend fun syncTimers() {
        val now = Clocks.moment()
        for (stored in dao.allTimerRuns().map { it.toModel() }) {
            var run = stored
            val endsAt = run.stepEndsAt
            if (run.state == TimerState.RUNNING && endsAt != null) {
                val rebased = endsAt.rebasedTo(now)
                if (rebased != endsAt) {
                    run = run.copy(stepEndsAt = rebased)
                    dao.upsertTimerRun(run.toEntity())
                }
                scheduler.setTimer(run.id, rebased.elapsed, TimerRules.signalAtStepEnd(run) == StepSignal.FULL)
            } else {
                scheduler.cancelTimer(run.id)
            }
            if (run.state == TimerState.RINGING) notifier.cancelTimer(run.id) else notifier.timer(run, now)
        }
        // «Не беспокоить» — пока идёт работа помидора с этим флажком (D-025, п. 1).
        FocusMode.apply(context, dao.allTimerRuns().any { it.toModel().wantsDnd })
    }

    suspend fun savePreset(preset: TimerPreset) = locked { dao.upsertPreset(preset.toEntity()) }

    suspend fun deletePreset(id: String) = locked { dao.deletePreset(id) }

    // Секундомер

    suspend fun updateStopwatch(change: (Stopwatch) -> Stopwatch) = locked {
        val old = dao.currentStopwatch().toModel()
        val updated = change(old)
        // Сброс — конец замера: сколько шёл и сколько кругов (D-023, п. 7).
        if (old.state != StopwatchState.RESET && updated.state == StopwatchState.RESET) {
            val elapsed = old.elapsedAt(Clocks.moment())
            if (elapsed > 0) logActivity(ActivityKind.STOPWATCH, now() - elapsed, now(), amountMillis = elapsed, count = old.laps.size)
        }
        dao.upsertStopwatch(updated.toEntity())
        notifier.stopwatch(updated)
    }

    /** Запись журнала в момент, когда сигнал кончился; пропущенный не звучал — кончился там же, где «зазвучал». */
    private suspend fun log(source: SignalSource, sourceId: String, scheduledAt: Long, firedAt: Long, outcome: SignalOutcome, title: String) {
        val endedAt = if (outcome == SignalOutcome.MISSED) firedAt else maxOf(firedAt, now())
        dao.insertEvent(
            SignalEventEntity(UUID.randomUUID().toString(), source.name, sourceId, scheduledAt, firedAt, outcome.name, title, endedAt),
        )
    }

    // Статистика (D-023, п. 7)

    /** Ночные часы и «пора спать» — с экрана и приёмника. */
    suspend fun recordActivity(kind: ActivityKind, startedAt: Long, endedAt: Long) = locked(quiet = true) {
        logActivity(kind, startedAt, endedAt)
    }

    /** «Очистить историю»: журнал срабатываний и действия. */
    suspend fun clearHistory() = locked(quiet = true) {
        dao.clearEvents()
        dao.clearActivities()
    }

    private suspend fun logActivity(
        kind: ActivityKind,
        startedAt: Long,
        endedAt: Long,
        title: String = "",
        refId: String? = null,
        amountMillis: Long = 0L,
        count: Int = 0,
    ) {
        dao.insertActivity(ActivityEvent(UUID.randomUUID().toString(), kind, startedAt, endedAt, title, refId, amountMillis, count).toEntity())
    }

    /**
     * Таймер кончился или убран; таймер засыпания уже записан при запуске, помидор —
     * по шагам работы. Бесконечный — сколько шёл.
     */
    private suspend fun logTimer(run: TimerRun, completed: Boolean) {
        if (run.program.sleep || run.program.isPomodoro) return
        val amount = if (run.program.forever) now() - run.createdAt else run.program.totalMillis
        logActivity(ActivityKind.TIMER, run.createdAt, now(), run.name, run.presetId, amount, if (completed) 1 else 0)
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        /** Предупреждение пришло чуть раньше срока (неточный будильник без разрешения) — всё равно его. */
        const val WARNING_SLACK_MILLIS = 30_000L
    }
}

/** Тихое напоминание пришло раньше срока больше чем на это — переставить (D-033). */
private const val NUDGE_SLACK_MILLIS = 5 * 60_000L
