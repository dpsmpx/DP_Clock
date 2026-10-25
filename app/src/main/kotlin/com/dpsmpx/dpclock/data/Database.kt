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
    /** Дни повтора битами (Repeat.bits); 0 — однократно или на дату. */
    val repeatDays: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val label: String,
    /** AlarmSound.encode(). */
    val sound: String,
    /** Название мелодии телефона или файла. */
    val soundTitle: String,
    val vibrate: Boolean,
    val rampSeconds: Int,
    /** null — общий интервал из настроек. */
    val snoozeMinutes: Int?,
    /** Пропускаемая дата, день от эпохи (LocalDate.toEpochDay). */
    val skipDate: Long?,
    /** Будильник на дату: день от эпохи; null — однократно или по дням недели. */
    val repeatDate: Long?,
    /** Не звонить в исключения (D-014, п. 4). */
    val honorExceptions: Boolean,
    /** Группа; null — без группы. */
    val groupId: String?,
    /** Сменный график: первый день цикла, день от эпохи; null — не график. */
    val cycleAnchor: Long?,
    /** Сменный график: Repeat.Cycle.encodeDays(). */
    val cycleDays: String?,
    /** Рабочие дни по производственному календарю России. */
    val workdays: Boolean,
    /** WakeMethod. */
    val wakeMethod: String,
    /** WakeLevel. */
    val wakeLevel: String,
    val wakeTasks: Int,
    /** Образец: текст QR-кода или подпись снимка места. */
    val wakeReference: String,
    val wakeSnooze: Boolean,
    /** Повторная проверка «Не спите?» через столько минут; 0 — нет. */
    val recheckMinutes: Int,
    /** Мягкое пробуждение за столько минут; 0 — нет (D-017). */
    val gentleMinutes: Int,
    /** После будильника: пресет таймера, напоминание, приложение (D-017). */
    val afterPresetId: String?,
    val afterReminder: String,
    val afterReminderMinutes: Int,
    val afterApp: String,
    /** Перед ранним событием календаря (D-023, п. 3): начало события, мс UTC; null — обычный. */
    val calendarEventAt: Long? = null,
)

/** Группа будильников (D-014, п. 5). */
@Entity(tableName = "alarm_group")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
)

/** Шаблон — набор будильников (D-014, п. 6). */
@Entity(tableName = "alarm_template")
data class TemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** AlarmTemplate.encodeAlarms(). */
    val alarms: String,
    val createdAt: Long,
)

/** Период общего списка исключений (D-014, п. 4); дни от эпохи, оба входят. */
@Entity(tableName = "exception_period")
data class ExceptionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val startDay: Long,
    val endDay: Long,
)

/** Текущее срабатывание будильника (DATA_MODEL.md, п. 3). */
@Entity(tableName = "alarm_occurrence")
data class OccurrenceEntity(
    @PrimaryKey val alarmId: String,
    /** Расчётное время срабатывания, мс UTC. */
    val scheduledAt: Long,
    /** SCHEDULED, RINGING, SNOOZED или RECHECK. */
    val state: String,
    val snoozedUntil: Long?,
    val ringingSince: Long?,
    /** Момент повторной проверки «Не спите?» (D-015, п. 4). */
    val recheckAt: Long?,
    /**
     * У звонящего: когда этот звонок ожидался — срок, конец «Отложить» или
     * момент повторного сигнала после «Не спите?». Для журнала (0.7.1).
     */
    val expectedAt: Long? = null,
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
    /** Показывать обратный отсчёт у включённых будильников. */
    val showAlarmCountdown: Boolean = false,
    val xiaomiHintsDone: Boolean,
    /** SignalStyle. */
    val signalStyle: String,
    /** VolumeButtons. */
    val volumeButtons: String,
    /** Праздники и переносы по производственному календарю России — в исключениях. */
    val holidaysRu: Boolean,
    /** Время сна (D-017): BedtimeMode, сколько спать, расписание, напомнить заранее. */
    val bedtimeMode: String,
    val sleepMinutes: Int,
    val bedtimeHour: Int,
    val bedtimeMinute: Int,
    /** Дни расписания битами, как у будильника. */
    val bedtimeDays: Int,
    val bedtimeLeadMinutes: Int,
    /** Погода (D-019): WeatherMode, название места и точка; точки нет — место не выбрано. */
    val weatherMode: String = "NOT_SET",
    val weatherPlace: String = "",
    val weatherLat: Double? = null,
    val weatherLon: Double? = null,
    /** Оформление (D-021): ThemeMode, цвет ARGB (null — из обоев), ClockStyle, ClockFont, размер, NoiseKind, ClockBackground. */
    val theme: String = "SYSTEM",
    val seedColor: Int? = null,
    val clockStyle: String = "CIRCLE",
    val clockFont: String = "REGULAR",
    val clockSize: Int = 100,
    val nightNoise: String = "NONE",
    val background: String = "NONE",
    /** «Высокий контраст» (D-022, п. 5). */
    val highContrast: Boolean = false,
    /** Фон ночных часов, фон приложения и обои Android (D-024): ClockBackground. */
    val nightBackground: String = "NONE",
    val appBackground: String = "NONE",
    val wallpaper: String = "CONSTELLATION",
)

/** Город мировых часов (D-019): пояс tz и место в списке. */
@Entity(tableName = "world_city")
data class WorldCityEntity(
    @PrimaryKey val zoneId: String,
    val position: Int,
    val createdAt: Long,
)

/** Журнал срабатываний (DATA_MODEL.md, п. 7). С 0.12.1 хранится весь: основа статистики (D-023, п. 7). */
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
    /** Название будильника или таймера на момент записи (0.7.1). */
    val title: String = "",
    /** Когда сигнал кончился — выключен, отложен, стих (0.12.1); у прежних записей — null. */
    val endedAt: Long? = null,
)

/** Действия для статистики (D-023, п. 7): ночные часы, таймер засыпания, «пора спать», таймеры, секундомер. */
@Entity(tableName = "activity_event")
data class ActivityEventEntity(
    @PrimaryKey val id: String,
    /** ActivityKind. */
    val kind: String,
    val startedAt: Long,
    val endedAt: Long,
    val title: String,
    val refId: String?,
    val amountMillis: Long,
    val count: Int,
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

    @Query("SELECT * FROM alarm WHERE groupId = :groupId")
    suspend fun alarmsInGroup(groupId: String): List<AlarmEntity>

    @Query("UPDATE alarm SET groupId = NULL WHERE groupId = :groupId")
    suspend fun ungroup(groupId: String)

    @Query("SELECT * FROM alarm_group ORDER BY createdAt")
    fun groups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM alarm_group WHERE id = :id")
    suspend fun group(id: String): GroupEntity?

    @Query("SELECT * FROM alarm_group ORDER BY createdAt")
    suspend fun allGroups(): List<GroupEntity>

    @Upsert
    suspend fun upsertGroup(group: GroupEntity)

    @Query("DELETE FROM alarm_group WHERE id = :id")
    suspend fun deleteGroup(id: String)

    @Query("SELECT * FROM alarm_template ORDER BY createdAt")
    fun templates(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM alarm_template WHERE id = :id")
    suspend fun template(id: String): TemplateEntity?

    @Query("SELECT * FROM alarm_template ORDER BY createdAt")
    suspend fun allTemplates(): List<TemplateEntity>

    @Upsert
    suspend fun upsertTemplate(template: TemplateEntity)

    @Query("DELETE FROM alarm_template WHERE id = :id")
    suspend fun deleteTemplate(id: String)

    @Query("SELECT * FROM exception_period ORDER BY startDay")
    fun exceptionsFlow(): Flow<List<ExceptionEntity>>

    @Query("SELECT * FROM exception_period ORDER BY startDay")
    suspend fun allExceptions(): List<ExceptionEntity>

    @Upsert
    suspend fun upsertException(period: ExceptionEntity)

    @Query("DELETE FROM exception_period WHERE id = :id")
    suspend fun deleteException(id: String)

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

    @Query("SELECT * FROM timer_preset WHERE id = :id")
    suspend fun preset(id: String): PresetEntity?

    @Query("SELECT * FROM timer_preset ORDER BY createdAt")
    suspend fun allPresets(): List<PresetEntity>

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

    @Query("SELECT * FROM signal_event ORDER BY firedAt DESC")
    fun events(): Flow<List<SignalEventEntity>>

    @Upsert
    suspend fun insertActivity(activity: ActivityEventEntity)

    @Query("SELECT * FROM activity_event ORDER BY startedAt DESC")
    fun activities(): Flow<List<ActivityEventEntity>>

    /** «Очистить историю» в статистике: журнал и действия. */
    @Query("DELETE FROM signal_event")
    suspend fun clearEvents()

    @Query("DELETE FROM activity_event")
    suspend fun clearActivities()

    /** Идущие таймеры, секундомер и срабатывания — состояние этого устройства, не для переноса. */
    @Query("DELETE FROM timer_run")
    suspend fun clearTimerRuns()

    @Query("DELETE FROM stopwatch")
    suspend fun clearStopwatch()

    @Query("DELETE FROM alarm_occurrence")
    suspend fun clearOccurrences()

    @Query("SELECT zoneId FROM world_city ORDER BY position, createdAt")
    fun worldCities(): Flow<List<String>>

    @Query("SELECT * FROM world_city ORDER BY position, createdAt")
    suspend fun allWorldCities(): List<WorldCityEntity>

    @Upsert
    suspend fun upsertWorldCity(city: WorldCityEntity)

    @Query("DELETE FROM world_city WHERE zoneId = :zoneId")
    suspend fun deleteWorldCity(zoneId: String)

    @Query("DELETE FROM world_city")
    suspend fun clearWorldCities()

    /** «Заменить всё» при загрузке из файла (D-018, п. 4). */
    @Query("DELETE FROM alarm_group")
    suspend fun clearGroups()

    @Query("DELETE FROM alarm_template")
    suspend fun clearTemplates()

    @Query("DELETE FROM exception_period")
    suspend fun clearExceptions()

    @Query("DELETE FROM timer_preset")
    suspend fun clearPresets()
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
        GroupEntity::class,
        TemplateEntity::class,
        ExceptionEntity::class,
        WorldCityEntity::class,
        ActivityEventEntity::class,
    ],
    version = ClockDatabase.VERSION,
    exportSchema = true,
)
abstract class ClockDatabase : RoomDatabase() {
    abstract fun dao(): ClockDao

    companion object {
        const val NAME = "dpclock.db"

        /** Версия схемы; новая — со схемой в `app/schemas` и миграцией в [MIGRATIONS]. */
        const val VERSION = 13

        /** Все миграции по порядку версий; `MigrationTest` проверяет каждую по выгруженным схемам. */
        val MIGRATIONS: List<SqlMigration> = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)

        /**
         * База в хранилище устройства: будильник звонит и до первой разблокировки
         * после перезагрузки (D-008, п. 5).
         */
        fun open(context: Context): ClockDatabase =
            Room.databaseBuilder(context.deviceStorage(), ClockDatabase::class.java, NAME)
                // Без журнала WAL всё лежит в одном файле — его и берёт системная копия.
                .setJournalMode(JournalMode.TRUNCATE)
                .addMigrations(*MIGRATIONS.toTypedArray())
                .build()
    }
}

/** Хранилище устройства — доступно до ввода PIN после перезагрузки. */
fun Context.deviceStorage(): Context = createDeviceProtectedStorageContext()
