package com.dpsmpx.dpclock.data

import android.content.Context
import com.dpsmpx.dpclock.model.Alarm
import com.dpsmpx.dpclock.model.AlarmGroup
import com.dpsmpx.dpclock.model.AlarmRules
import com.dpsmpx.dpclock.model.AlarmTemplate
import com.dpsmpx.dpclock.model.ExceptionPeriod
import com.dpsmpx.dpclock.model.Exceptions
import com.dpsmpx.dpclock.model.Repeat
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.SignalOutcome
import com.dpsmpx.dpclock.model.SignalSource
import com.dpsmpx.dpclock.model.StepSignal
import com.dpsmpx.dpclock.model.Stopwatch
import com.dpsmpx.dpclock.model.TimerPreset
import com.dpsmpx.dpclock.model.TimerProgram
import com.dpsmpx.dpclock.model.TimerRules
import com.dpsmpx.dpclock.model.TimerRun
import com.dpsmpx.dpclock.model.TimerState
import com.dpsmpx.dpclock.model.isRepeating
import com.dpsmpx.dpclock.platform.Clocks
import com.dpsmpx.dpclock.platform.Notifier
import com.dpsmpx.dpclock.platform.Ringing
import com.dpsmpx.dpclock.platform.Scheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
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

/** Что делать с концом шага таймера. */
data class TimerFire(val run: TimerRun, val signal: StepSignal)

/**
 * Все изменения данных и расписаний — здесь, по одному за раз: после каждого
 * изменения будильники Android и уведомления приводятся в соответствие с базой.
 */
class ClockRepository(
    context: Context,
    db: ClockDatabase,
    private val scheduler: Scheduler,
    private val notifier: Notifier,
) {
    private val dao = db.dao()
    private val mutex = Mutex()
    private val runtimeMarker = File(context.deviceStorage().noBackupFilesDir, "runtime-owner")
    private var ready = false

    val alarms: Flow<List<Alarm>> = dao.alarms().map { list -> list.map { it.toModel() } }
    val settings: Flow<Settings> = dao.settings().map { it.toModel() }
    val presets: Flow<List<TimerPreset>> = dao.presets().map { list -> list.map { it.toModel() } }
    val timerRuns: Flow<List<TimerRun>> = dao.timerRuns().map { list -> list.map { it.toModel() } }
    val stopwatch: Flow<Stopwatch> = dao.stopwatch().map { it.toModel() }
    val groups: Flow<List<AlarmGroup>> = dao.groups().map { list -> list.map { it.toModel() } }
    val templates: Flow<List<AlarmTemplate>> = dao.templates().map { list -> list.map { it.toModel() } }

    /** Общий список исключений: свои периоды и праздники, если включены (D-014, п. 4). */
    val exceptions: Flow<Exceptions> = combine(dao.exceptionsFlow(), settings) { periods, settings ->
        Exceptions(periods.map { it.toModel() }, settings.holidaysRu)
    }

    /** Отложенные будильники: id → конец «Отложить». */
    val snoozed: Flow<Map<String, Long>> = dao.occurrencesFlow().map { list ->
        list.filter { it.state == Occ.SNOOZED && it.snoozedUntil != null }.associate { it.alarmId to it.snoozedUntil!! }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock {
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
        block()
    }

    suspend fun currentSettings(): Settings = locked { dao.currentSettings().toModel() }

    suspend fun updateSettings(change: (Settings) -> Settings) = locked {
        val old = dao.currentSettings().toModel()
        val new = change(old)
        dao.upsertSettings(new.toEntity())
        // Праздники включили или выключили — у будильников другие даты.
        if (old.holidaysRu != new.holidaysRu) rescheduleAllLocked()
    }

    // Будильники

    /** Новый будильник — однократный и включённый (SCREEN_MAP.md, п. 1), можно сразу в группе. Возвращает момент срабатывания. */
    suspend fun addAlarm(hour: Int, minute: Int, groupId: String? = null): Long = locked {
        val alarm = Alarm(UUID.randomUUID().toString(), hour, minute, Repeat.Once, enabled = true, createdAt = now(), groupId = groupId)
        dao.upsertAlarm(alarm.toEntity())
        scheduleAlarm(alarm)
    }

    /** Сохранить изменённый будильник; прежнее срабатывание и «Отложить» отменяются. */
    suspend fun saveAlarm(alarm: Alarm): Long = locked {
        val stored = normalized(alarm)
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
            }
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
        dao.deleteAlarm(id)
        dao.deleteOccurrence(id)
        scheduler.cancelAlarm(id)
        notifier.cancelSnoozed(id)
        alarm
    }

    /** «Отменить» после удаления. */
    suspend fun restoreAlarm(alarm: Alarm) = locked {
        dao.upsertAlarm(alarm.toEntity())
        scheduleAlarm(alarm)
    }

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
        dao.upsertOccurrence(occ.copy(state = Occ.RINGING, ringingSince = now, snoozedUntil = null))
        // Однократный и на дату выполнены — выключаются сразу, «Отложить» их не возвращает в список.
        if (!alarm.repeat.isRepeating) dao.upsertAlarm(alarm.copy(enabled = false).toEntity())
        notifier.cancelSnoozed(id)
        AlarmFire.Ring(alarm, occ.scheduledAt, snoozeMinutes(alarm))
    }

    /** «Отложить»: возвращает конец интервала или null, если будильник не звонит. */
    suspend fun snoozeAlarm(id: String): Long? = locked {
        val occ = dao.occurrence(id)?.takeIf { it.state == Occ.RINGING } ?: return@locked null
        val now = now()
        val alarm = dao.alarm(id)?.toModel()
        val until = now + (alarm?.let { snoozeMinutes(it) } ?: dao.currentSettings().toModel().snoozeMinutes) * 60_000L
        dao.upsertOccurrence(occ.copy(state = Occ.SNOOZED, snoozedUntil = until, ringingSince = null))
        log(SignalSource.ALARM, id, occ.scheduledAt, occ.ringingSince ?: now, SignalOutcome.SNOOZED)
        scheduler.setAlarm(id, until)
        alarm?.let { notifier.snoozed(it, until) }
        until
    }

    /** «Выключить» — из сигнала или из уведомления об отложенном; автовыключение — тоже сюда. */
    suspend fun stopAlarm(id: String, outcome: SignalOutcome) = locked {
        val occ = dao.occurrence(id)
        val now = now()
        val recheck = dao.alarm(id)?.toModel()?.wakeCheck?.recheckMinutes ?: 0
        if (outcome == SignalOutcome.DISMISSED && recheck > 0 && occ != null && occ.state != Occ.RECHECK) {
            // Выключен, но через несколько минут — «Не спите?» (D-011, п. 3; D-015, п. 4).
            val at = now + recheck * 60_000L
            dao.upsertOccurrence(occ.copy(state = Occ.RECHECK, snoozedUntil = null, ringingSince = null, recheckAt = at))
            log(SignalSource.ALARM, id, occ.scheduledAt, occ.ringingSince ?: now, outcome)
            notifier.cancelSnoozed(id)
            scheduler.setAlarm(id, at)
            return@locked
        }
        dao.deleteOccurrence(id)
        scheduler.cancelAlarm(id)
        notifier.cancelSnoozed(id)
        val alarm = dao.alarm(id)?.toModel() ?: return@locked
        if (occ != null) log(SignalSource.ALARM, id, occ.scheduledAt, occ.ringingSince ?: now, outcome)
        if (outcome == SignalOutcome.AUTO_STOPPED) notifier.missedAlarm(alarm, occ?.scheduledAt ?: now, autoStopped = true)
        scheduleAlarm(alarm)
    }

    /** «Я не сплю» — проверка пройдена: будильник идёт к следующему срабатыванию. */
    suspend fun recheckPassed(id: String) = locked {
        val occ = dao.occurrence(id)?.takeIf { it.state == Occ.RECHECK } ?: return@locked
        dao.deleteOccurrence(id)
        scheduler.cancelAlarm(id)
        dao.alarm(id)?.toModel()?.let { scheduleAlarm(it) }
        log(SignalSource.ALARM, id, occ.scheduledAt, now(), SignalOutcome.DISMISSED)
    }

    /** На «Не спите?» не ответили: будильник звонит снова. null — звонить нечему. */
    suspend fun recheckFailed(id: String): AlarmFire.Ring? = locked {
        val occ = dao.occurrence(id)?.takeIf { it.state == Occ.RECHECK } ?: return@locked null
        val alarm = dao.alarm(id)?.toModel() ?: return@locked null
        dao.upsertOccurrence(occ.copy(state = Occ.RINGING, ringingSince = now(), recheckAt = null))
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
    }

    private suspend fun currentExceptions(): Exceptions =
        Exceptions(dao.allExceptions().map { it.toModel() }, dao.currentSettings().toModel().holidaysRu)

    /** У сменного графика время будильника — время первого рабочего дня: по нему сортируется список. */
    private fun normalized(alarm: Alarm): Alarm {
        val repeat = alarm.repeat as? Repeat.Cycle ?: return alarm
        return alarm.copy(hour = repeat.firstTime.hour, minute = repeat.firstTime.minute)
    }

    /** Интервал «Отложить» будильника: свой или общий из настроек (D-013, п. 3). */
    private suspend fun snoozeMinutes(alarm: Alarm): Int =
        alarm.snoozeMinutes ?: dao.currentSettings().toModel().snoozeMinutes

    private suspend fun resetOccurrence(id: String) {
        val occ = dao.occurrence(id) ?: return
        if (occ.state == Occ.RINGING) return
        dao.deleteOccurrence(id)
        notifier.cancelSnoozed(id)
    }

    /** Поставить ближайшее срабатывание; возвращает его момент (0 — будильник выключен или больше не сработает). */
    private suspend fun scheduleAlarm(stored: Alarm, knownExceptions: Exceptions? = null): Long {
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
            return 0L
        }
        val at = AlarmRules.nextTrigger(alarm, nowInstant, zone, knownExceptions ?: currentExceptions())?.toEpochMilli()
        if (at == null) {
            // Дата прошла — будильник больше не сработает и выключается. У повторяющегося
            // все даты закрыли исключения: он остаётся включённым до их правки.
            if (!alarm.repeat.isRepeating) dao.upsertAlarm(alarm.copy(enabled = false).toEntity())
            dao.deleteOccurrence(alarm.id)
            scheduler.cancelAlarm(alarm.id)
            return 0L
        }
        dao.upsertOccurrence(OccurrenceEntity(alarm.id, at, Occ.SCHEDULED, snoozedUntil = null, ringingSince = null, recheckAt = null))
        scheduler.setAlarm(alarm.id, at)
        return at
    }

    /** Пропуск (DATA_MODEL.md, п. 2): уведомление, однократный выключается, повторяющийся — к следующему. */
    private suspend fun missAlarm(alarm: Alarm, scheduledAt: Long, now: Long) {
        log(SignalSource.ALARM, alarm.id, scheduledAt, now, SignalOutcome.MISSED)
        notifier.missedAlarm(alarm, scheduledAt, autoStopped = false)
        val next = if (alarm.repeat.isRepeating) alarm else alarm.copy(enabled = false)
        dao.upsertAlarm(next.toEntity())
        dao.deleteOccurrence(alarm.id)
        scheduleAlarm(next)
    }

    // Таймеры

    suspend fun startTimer(program: TimerProgram, name: String, presetId: String?) = locked {
        val run = TimerRules.start(UUID.randomUUID().toString(), presetId, name, program, Clocks.moment())
        dao.upsertTimerRun(run.toEntity())
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
        dao.deleteTimerRun(id)
        scheduler.cancelTimer(id)
        notifier.cancelTimer(id)
    }

    /** Звонящий таймер выключен: закончившийся удаляется, промежуточный шаг идёт дальше. */
    suspend fun stopTimerSignal(id: String, outcome: SignalOutcome) = locked {
        val run = dao.timerRun(id)?.toModel() ?: return@locked
        val now = now()
        log(SignalSource.TIMER, id, now, now, outcome)
        if (run.state == TimerState.RINGING) {
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
        if (result.run.state == TimerState.RINGING && result.late) {
            // Закончился, пока телефон был выключен: только уведомление (DATA_MODEL.md, п. 5).
            dao.deleteTimerRun(id)
            scheduler.cancelTimer(id)
            notifier.cancelTimer(id)
            notifier.timerFinished(run)
            log(SignalSource.TIMER, id, now.utc, now.utc, SignalOutcome.MISSED)
            signal = StepSignal.NONE
        } else {
            dao.upsertTimerRun(result.run.toEntity())
        }
        syncTimers()
        TimerFire(result.run, signal)
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
    }

    suspend fun savePreset(preset: TimerPreset) = locked { dao.upsertPreset(preset.toEntity()) }

    suspend fun deletePreset(id: String) = locked { dao.deletePreset(id) }

    // Секундомер

    suspend fun updateStopwatch(change: (Stopwatch) -> Stopwatch) = locked {
        val updated = change(dao.currentStopwatch().toModel())
        dao.upsertStopwatch(updated.toEntity())
        notifier.stopwatch(updated)
    }

    private suspend fun log(source: SignalSource, sourceId: String, scheduledAt: Long, firedAt: Long, outcome: SignalOutcome) {
        dao.insertEvent(
            SignalEventEntity(UUID.randomUUID().toString(), source.name, sourceId, scheduledAt, firedAt, outcome.name),
        )
        dao.trimEvents()
    }

    private fun now(): Long = System.currentTimeMillis()
}
