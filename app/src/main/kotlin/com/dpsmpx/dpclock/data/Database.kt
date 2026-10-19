package com.dpsmpx.dpclock.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.JournalMode
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// Схема — DATA_MODEL.md, хранение — D-009. Каждая новая версия схемы — миграция с тестом.

@Entity(tableName = "alarm")
data class AlarmEntity(
    @PrimaryKey val id: String,
    val hour: Int,
    val minute: Int,
    /** Дни повтора битами (Repeat.bits); 0 — однократно. */
    val repeatDays: Int,
    val enabled: Boolean,
    val createdAt: Long,
)

/** Текущее срабатывание будильника (DATA_MODEL.md, п. 3). */
@Entity(tableName = "alarm_occurrence")
data class OccurrenceEntity(
    @PrimaryKey val alarmId: String,
    /** Расчётное время срабатывания, мс UTC. */
    val scheduledAt: Long,
    /** SCHEDULED, RINGING или SNOOZED. */
    val state: String,
    val snoozedUntil: Long?,
    val ringingSince: Long?,
)

@Entity(tableName = "timer_preset")
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** TimerProgram.encode(). */
    val program: String,
    val createdAt: Long,
)

@Entity(tableName = "timer_run")
data class TimerRunEntity(
    @PrimaryKey val id: String,
    val presetId: String?,
    val name: String,
    val program: String,
    /** TimerState. */
    val state: String,
    val stepIndex: Int,
    val cycleIndex: Int,
    val stepEndsAtElapsed: Long?,
    val bootCount: Int?,
    val stepEndsAtUtc: Long?,
    val remainingMillis: Long,
    val createdAt: Long,
)

/** Секундомер — одна строка с id 0. */
@Entity(tableName = "stopwatch")
data class StopwatchEntity(
    @PrimaryKey val id: Int = 0,
    val state: String,
    val startedAtElapsed: Long?,
    val bootCount: Int?,
    val startedAtUtc: Long?,
    val accumulatedMillis: Long,
    /** Общее время кругов через запятую. */
    val laps: String,
)

/** Настройки — одна строка с id 0. */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 0,
    val snoozeMinutes: Int,
    /** null — «никогда». */
    val autoStopMinutes: Int?,
    val alarmOrder: String,
    val xiaomiHintsDone: Boolean,
)

/** Журнал срабатываний (DATA_MODEL.md, п. 7), последние [ClockDao.LOG_LIMIT] событий. */
@Entity(tableName = "signal_event")
data class SignalEventEntity(
    @PrimaryKey val id: String,
    /** SignalSource. */
    val source: String,
    val sourceId: String,
    val scheduledAt: Long,
    val firedAt: Long,
    /** SignalOutcome. */
    val outcome: String,
)

@Dao
interface ClockDao {
    @Query("SELECT * FROM alarm")
    fun alarms(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarm")
    suspend fun allAlarms(): List<AlarmEntity>

    @Query("SELECT * FROM alarm WHERE id = :id")
    suspend fun alarm(id: String): AlarmEntity?

    @Upsert
    suspend fun upsertAlarm(alarm: AlarmEntity)

    @Query("DELETE FROM alarm WHERE id = :id")
    suspend fun deleteAlarm(id: String)

    @Query("SELECT * FROM alarm_occurrence")
    suspend fun occurrences(): List<OccurrenceEntity>

    @Query("SELECT * FROM alarm_occurrence")
    fun occurrencesFlow(): Flow<List<OccurrenceEntity>>

    @Query("SELECT * FROM alarm_occurrence WHERE alarmId = :alarmId")
    suspend fun occurrence(alarmId: String): OccurrenceEntity?

    @Upsert
    suspend fun upsertOccurrence(occurrence: OccurrenceEntity)

    @Query("DELETE FROM alarm_occurrence WHERE alarmId = :alarmId")
    suspend fun deleteOccurrence(alarmId: String)

    @Query("SELECT * FROM timer_preset ORDER BY createdAt")
    fun presets(): Flow<List<PresetEntity>>

    @Upsert
    suspend fun upsertPreset(preset: PresetEntity)

    @Query("DELETE FROM timer_preset WHERE id = :id")
    suspend fun deletePreset(id: String)

    @Query("SELECT * FROM timer_run ORDER BY createdAt")
    fun timerRuns(): Flow<List<TimerRunEntity>>

    @Query("SELECT * FROM timer_run ORDER BY createdAt")
    suspend fun allTimerRuns(): List<TimerRunEntity>

    @Query("SELECT * FROM timer_run WHERE id = :id")
    suspend fun timerRun(id: String): TimerRunEntity?

    @Upsert
    suspend fun upsertTimerRun(run: TimerRunEntity)

    @Query("DELETE FROM timer_run WHERE id = :id")
    suspend fun deleteTimerRun(id: String)

    @Query("SELECT * FROM stopwatch WHERE id = 0")
    fun stopwatch(): Flow<StopwatchEntity?>

    @Query("SELECT * FROM stopwatch WHERE id = 0")
    suspend fun currentStopwatch(): StopwatchEntity?

    @Upsert
    suspend fun upsertStopwatch(stopwatch: StopwatchEntity)

    @Query("SELECT * FROM settings WHERE id = 0")
    fun settings(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 0")
    suspend fun currentSettings(): SettingsEntity?

    @Upsert
    suspend fun upsertSettings(settings: SettingsEntity)

    @Upsert
    suspend fun insertEvent(event: SignalEventEntity)

    @Query(
        "DELETE FROM signal_event WHERE id NOT IN " +
            "(SELECT id FROM signal_event ORDER BY firedAt DESC LIMIT $LOG_LIMIT)",
    )
    suspend fun trimEvents()

    /** Идущие таймеры, секундомер и срабатывания — состояние этого устройства, не для переноса. */
    @Query("DELETE FROM timer_run")
    suspend fun clearTimerRuns()

    @Query("DELETE FROM stopwatch")
    suspend fun clearStopwatch()

    @Query("DELETE FROM alarm_occurrence")
    suspend fun clearOccurrences()

    companion object {
        const val LOG_LIMIT = 1000
    }
}

@Database(
    entities = [
        AlarmEntity::class,
        OccurrenceEntity::class,
        PresetEntity::class,
        TimerRunEntity::class,
        StopwatchEntity::class,
        SettingsEntity::class,
        SignalEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ClockDatabase : RoomDatabase() {
    abstract fun dao(): ClockDao

    companion object {
        const val NAME = "dpclock.db"

        /**
         * База в хранилище устройства: будильник звонит и до первой разблокировки
         * после перезагрузки (D-008, п. 5).
         */
        fun open(context: Context): ClockDatabase =
            Room.databaseBuilder(context.deviceStorage(), ClockDatabase::class.java, NAME)
                // Без журнала WAL всё лежит в одном файле — его и берёт системная копия.
                .setJournalMode(JournalMode.TRUNCATE)
                .build()
    }
}

/** Хранилище устройства — доступно до ввода PIN после перезагрузки. */
fun Context.deviceStorage(): Context = createDeviceProtectedStorageContext()
