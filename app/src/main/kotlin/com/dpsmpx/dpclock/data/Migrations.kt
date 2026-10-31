package com.dpsmpx.dpclock.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/**
 * Миграция схемы (D-009, п. 4) — список SQL. Тот же список выполняет Room на
 * телефоне и `MigrationTest` на JVM со встроенным SQLite.
 */
class SqlMigration(from: Int, to: Int, val statements: List<String>) : Migration(from, to) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (sql in statements) db.execSQL(sql)
    }

    override fun migrate(connection: SQLiteConnection) {
        for (sql in statements) connection.execSQL(sql)
    }
}

/** 0.2.0 (D-013): будильник целиком — название, звук, вибрация, нарастание, «Отложить», пропуск, дата; вид сигнала и кнопки громкости. */
val MIGRATION_1_2 = SqlMigration(
    1,
    2,
    listOf(
        "ALTER TABLE `alarm` ADD COLUMN `label` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `alarm` ADD COLUMN `sound` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `alarm` ADD COLUMN `soundTitle` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `alarm` ADD COLUMN `vibrate` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `alarm` ADD COLUMN `rampSeconds` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `alarm` ADD COLUMN `snoozeMinutes` INTEGER",
        "ALTER TABLE `alarm` ADD COLUMN `skipDate` INTEGER",
        "ALTER TABLE `alarm` ADD COLUMN `repeatDate` INTEGER",
        "ALTER TABLE `settings` ADD COLUMN `signalStyle` TEXT NOT NULL DEFAULT 'FULL_SCREEN'",
        "ALTER TABLE `settings` ADD COLUMN `volumeButtons` TEXT NOT NULL DEFAULT 'SNOOZE'",
    ),
)

/** 0.3.0 (D-014): сменные графики, рабочие дни, исключения, группы и шаблоны. */
val MIGRATION_2_3 = SqlMigration(
    2,
    3,
    listOf(
        "ALTER TABLE `alarm` ADD COLUMN `honorExceptions` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `alarm` ADD COLUMN `groupId` TEXT",
        "ALTER TABLE `alarm` ADD COLUMN `cycleAnchor` INTEGER",
        "ALTER TABLE `alarm` ADD COLUMN `cycleDays` TEXT",
        "ALTER TABLE `alarm` ADD COLUMN `workdays` INTEGER NOT NULL DEFAULT 0",
        "CREATE TABLE IF NOT EXISTS `alarm_group` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `alarm_template` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `alarms` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `exception_period` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `startDay` INTEGER NOT NULL, " +
            "`endDay` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "ALTER TABLE `settings` ADD COLUMN `holidaysRu` INTEGER NOT NULL DEFAULT 0",
    ),
)

/** 0.4.0 (D-015): подтверждение пробуждения и повторная проверка «Не спите?». */
val MIGRATION_3_4 = SqlMigration(
    3,
    4,
    listOf(
        "ALTER TABLE `alarm` ADD COLUMN `wakeMethod` TEXT NOT NULL DEFAULT 'NONE'",
        "ALTER TABLE `alarm` ADD COLUMN `wakeLevel` TEXT NOT NULL DEFAULT 'MEDIUM'",
        "ALTER TABLE `alarm` ADD COLUMN `wakeTasks` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `alarm` ADD COLUMN `wakeReference` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `alarm` ADD COLUMN `wakeSnooze` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `alarm` ADD COLUMN `recheckMinutes` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `alarm_occurrence` ADD COLUMN `recheckAt` INTEGER",
    ),
)

/** 0.6.0 (D-017): мягкое пробуждение, действие после будильника, время сна. */
val MIGRATION_4_5 = SqlMigration(
    4,
    5,
    listOf(
        "ALTER TABLE `alarm` ADD COLUMN `gentleMinutes` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `alarm` ADD COLUMN `afterPresetId` TEXT",
        "ALTER TABLE `alarm` ADD COLUMN `afterReminder` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `alarm` ADD COLUMN `afterReminderMinutes` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `alarm` ADD COLUMN `afterApp` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `settings` ADD COLUMN `bedtimeMode` TEXT NOT NULL DEFAULT 'OFF'",
        "ALTER TABLE `settings` ADD COLUMN `sleepMinutes` INTEGER NOT NULL DEFAULT 480",
        "ALTER TABLE `settings` ADD COLUMN `bedtimeHour` INTEGER NOT NULL DEFAULT 23",
        "ALTER TABLE `settings` ADD COLUMN `bedtimeMinute` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `settings` ADD COLUMN `bedtimeDays` INTEGER NOT NULL DEFAULT 127",
        "ALTER TABLE `settings` ADD COLUMN `bedtimeLeadMinutes` INTEGER NOT NULL DEFAULT 15",
    ),
)

/** 0.7.1: журнал срабатываний — название и ожидаемое время звонка. */
val MIGRATION_5_6 = SqlMigration(
    5,
    6,
    listOf(
        "ALTER TABLE `alarm_occurrence` ADD COLUMN `expectedAt` INTEGER",
        "ALTER TABLE `signal_event` ADD COLUMN `title` TEXT NOT NULL DEFAULT ''",
    ),
)

/** 0.8.0 (D-019): города мировых часов и погода вашего города. */
val MIGRATION_6_7 = SqlMigration(
    6,
    7,
    listOf(
        "CREATE TABLE IF NOT EXISTS `world_city` (`zoneId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`zoneId`))",
        "ALTER TABLE `settings` ADD COLUMN `weatherMode` TEXT NOT NULL DEFAULT 'NOT_SET'",
        "ALTER TABLE `settings` ADD COLUMN `weatherPlace` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `settings` ADD COLUMN `weatherLat` REAL",
        "ALTER TABLE `settings` ADD COLUMN `weatherLon` REAL",
    ),
)

/** 0.10.0 (D-021): тема, цвет, стиль часов, шум ночных часов, фон. */
val MIGRATION_7_8 = SqlMigration(
    7,
    8,
    listOf(
        "ALTER TABLE `settings` ADD COLUMN `theme` TEXT NOT NULL DEFAULT 'SYSTEM'",
        "ALTER TABLE `settings` ADD COLUMN `seedColor` INTEGER",
        "ALTER TABLE `settings` ADD COLUMN `clockStyle` TEXT NOT NULL DEFAULT 'CIRCLE'",
        "ALTER TABLE `settings` ADD COLUMN `clockFont` TEXT NOT NULL DEFAULT 'REGULAR'",
        "ALTER TABLE `settings` ADD COLUMN `clockSize` INTEGER NOT NULL DEFAULT 100",
        "ALTER TABLE `settings` ADD COLUMN `nightNoise` TEXT NOT NULL DEFAULT 'NONE'",
        "ALTER TABLE `settings` ADD COLUMN `background` TEXT NOT NULL DEFAULT 'NONE'",
    ),
)

/** 0.11.0 (D-022, п. 5): «Высокий контраст» в оформлении. */
val MIGRATION_8_9 = SqlMigration(
    8,
    9,
    listOf("ALTER TABLE `settings` ADD COLUMN `highContrast` INTEGER NOT NULL DEFAULT 0"),
)

/** 0.12.0 (D-023, п. 3): будильник перед ранним событием календаря. */
val MIGRATION_9_10 = SqlMigration(
    9,
    10,
    listOf("ALTER TABLE `alarm` ADD COLUMN `calendarEventAt` INTEGER"),
)

/** 0.12.1 (D-023, п. 7): конец сигнала в журнале и действия для статистики. */
val MIGRATION_10_11 = SqlMigration(
    10,
    11,
    listOf(
        "ALTER TABLE `signal_event` ADD COLUMN `endedAt` INTEGER",
        "CREATE TABLE IF NOT EXISTS `activity_event` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
            "`endedAt` INTEGER NOT NULL, `title` TEXT NOT NULL, `refId` TEXT, `amountMillis` INTEGER NOT NULL, `count` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))",
    ),
)

/** 0.12.2: необязательный обратный отсчёт в строках будильников; выключен после обновления. */
val MIGRATION_11_12 = SqlMigration(
    11,
    12,
    listOf("ALTER TABLE `settings` ADD COLUMN `showAlarmCountdown` INTEGER NOT NULL DEFAULT 0"),
)

/** 0.13.0 (D-024): фон ночных часов, фон приложения, эффект обоев Android. */
val MIGRATION_12_13 = SqlMigration(
    12,
    13,
    listOf(
        "ALTER TABLE `settings` ADD COLUMN `nightBackground` TEXT NOT NULL DEFAULT 'NONE'",
        "ALTER TABLE `settings` ADD COLUMN `appBackground` TEXT NOT NULL DEFAULT 'NONE'",
        "ALTER TABLE `settings` ADD COLUMN `wallpaper` TEXT NOT NULL DEFAULT 'CONSTELLATION'",
    ),
)

/** 0.14.0 (D-025): сложный повтор, точный пропуск, предупреждения; дата в строке, куранты, помидор. */
val MIGRATION_13_14 = SqlMigration(
    13,
    14,
    listOf(
        "ALTER TABLE `alarm` ADD COLUMN `repeatRules` TEXT",
        "ALTER TABLE `alarm` ADD COLUMN `skipTime` INTEGER",
        "ALTER TABLE `alarm` ADD COLUMN `warnings` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `settings` ADD COLUMN `showAlarmDate` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `settings` ADD COLUMN `defaultWarnings` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `settings` ADD COLUMN `warningSound` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `settings` ADD COLUMN `chimes` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `settings` ADD COLUMN `pomodoro` TEXT NOT NULL DEFAULT ''",
    ),
)

/**
 * 0.15.0 (D-027): фон один на всё приложение. Не было фона «Часов» — им становится фон
 * приложения, иначе фон ночных часов; прежние столбцы обнуляются. Схема не меняется.
 */
val MIGRATION_14_15 = SqlMigration(
    14,
    15,
    listOf(
        "UPDATE `settings` SET `background` = CASE " +
            "WHEN `background` = 'NONE' AND `appBackground` != 'NONE' THEN `appBackground` " +
            "WHEN `background` = 'NONE' AND `nightBackground` != 'NONE' THEN `nightBackground` " +
            "ELSE `background` END, `appBackground` = 'NONE', `nightBackground` = 'NONE'",
    ),
)

/**
 * 0.16.0 (D-029): вибро-отклик кнопок — включён; первый запуск у тех, кто уже пользуется
 * приложением, считается пройденным — подсказки им не нужны.
 */
val MIGRATION_15_16 = SqlMigration(
    15,
    16,
    listOf(
        "ALTER TABLE `settings` ADD COLUMN `onboardingDone` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `settings` ADD COLUMN `haptics` INTEGER NOT NULL DEFAULT 1",
    ),
)
