package com.dpsmpx.dpclock.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Миграции базы на JVM со встроенным SQLite (D-009, п. 4): схема версии N из
 * выгруженного JSON, миграция, сверка со схемой N+1 по правилам Room — столбцы,
 * типы, NOT NULL, ключи, индексы. Значение по умолчанию сверяется, только если
 * оно есть в новой схеме: Room поступает так же.
 */
class MigrationTest {
    private fun <T> withDb(block: (SQLiteConnection) -> T): T =
        BundledSQLiteDriver().open(":memory:").use(block)

    @Test
    fun everySchemaVersionHasOneMigrationToTheNext() {
        val versions = SchemaFile.versions()
        assertEquals("выгруженные схемы", (1..ClockDatabase.VERSION).toList(), versions)
        for (from in versions.dropLast(1)) {
            val steps = ClockDatabase.MIGRATIONS.filter { it.startVersion == from }
            assertEquals("миграции из версии $from", listOf(from + 1), steps.map { it.endVersion })
        }
    }

    @Test
    fun eachMigrationProducesTheNextSchema() {
        for (migration in ClockDatabase.MIGRATIONS) {
            val migrated = withDb { db ->
                SchemaFile.read(migration.startVersion).create(db)
                migration.migrate(db)
                describe(db)
            }
            val expected = withDb { db ->
                SchemaFile.read(migration.endVersion).create(db)
                describe(db)
            }
            assertEquals("таблицы после ${migration.startVersion}→${migration.endVersion}", expected.keys, migrated.keys)
            for ((table, columns) in expected) {
                val actual = migrated.getValue(table)
                assertEquals("столбцы $table", columns.columns.keys, actual.columns.keys)
                for ((name, column) in columns.columns) {
                    val got = actual.columns.getValue(name)
                    assertEquals("$table.$name", column.copy(default = null), got.copy(default = null))
                    if (column.default != null) assertEquals("$table.$name: по умолчанию", column.default, got.default)
                }
                assertEquals("индексы $table", columns.indices, actual.indices)
                assertEquals("внешние ключи $table", columns.foreignKeys, actual.foreignKeys)
            }
        }
    }

    @Test
    fun migration11To12KeepsSettingsAndDefaultsCountdownOff() = withDb { db ->
        SchemaFile.read(11).create(db)
        db.execSQL(
            "INSERT INTO settings (id, snoozeMinutes, autoStopMinutes, alarmOrder, xiaomiHintsDone, signalStyle, volumeButtons, " +
                "holidaysRu, bedtimeMode, sleepMinutes, bedtimeHour, bedtimeMinute, bedtimeDays, bedtimeLeadMinutes, " +
                "weatherMode, weatherPlace, weatherLat, weatherLon, theme, seedColor, clockStyle, clockFont, clockSize, " +
                "nightNoise, background, highContrast) VALUES " +
                "(0, 15, NULL, 'NEXT', 1, 'NOTIFICATION', 'NOTHING', 1, 'SCHEDULE', 450, 22, 30, 3, 30, " +
                "'CITY', 'Tula', 54.1931, 37.6177, 'DARK', NULL, 'DIGITS', 'BOLD', 90, 'PINK', 'STARS', 1)",
        )

        MIGRATION_11_12.migrate(db)

        val settings = row(db, "SELECT * FROM settings")
        assertEquals("15", settings["snoozeMinutes"])
        assertEquals("NEXT", settings["alarmOrder"])
        assertEquals("NOTIFICATION", settings["signalStyle"])
        assertEquals("1", settings["highContrast"])
        assertEquals("0", settings["showAlarmCountdown"])
    }

    @Test
    fun migration1To2KeepsAlarmsAndSettings() = withDb { db ->
        SchemaFile.read(1).create(db)
        db.execSQL("INSERT INTO alarm (id, hour, minute, repeatDays, enabled, createdAt) VALUES ('a', 7, 30, 31, 1, 1000)")
        db.execSQL("INSERT INTO settings (id, snoozeMinutes, autoStopMinutes, alarmOrder, xiaomiHintsDone) VALUES (0, 15, NULL, 'NEXT', 1)")
        MIGRATION_1_2.migrate(db)

        val alarm = row(db, "SELECT * FROM alarm")
        assertEquals("a", alarm["id"])
        assertEquals("7", alarm["hour"])
        assertEquals("31", alarm["repeatDays"])
        assertEquals("", alarm["label"])
        assertEquals("", alarm["sound"])
        assertEquals("1", alarm["vibrate"])
        assertEquals("0", alarm["rampSeconds"])
        assertEquals(null, alarm["snoozeMinutes"])
        assertEquals(null, alarm["skipDate"])
        assertEquals(null, alarm["repeatDate"])

        val settings = row(db, "SELECT * FROM settings")
        assertEquals("15", settings["snoozeMinutes"])
        assertEquals("NEXT", settings["alarmOrder"])
        assertEquals("FULL_SCREEN", settings["signalStyle"])
        assertEquals("SNOOZE", settings["volumeButtons"])

        // Строка базы читается моделью: значения по умолчанию — те же, что у нового будильника.
        val model = AlarmEntity(
            id = "a", hour = 7, minute = 30, repeatDays = 31, enabled = true, createdAt = 1000,
            label = alarm.getValue("label")!!, sound = alarm.getValue("sound")!!, soundTitle = alarm.getValue("soundTitle")!!,
            vibrate = alarm["vibrate"] == "1", rampSeconds = alarm.getValue("rampSeconds")!!.toInt(),
            snoozeMinutes = null, skipDate = null, repeatDate = null,
            honorExceptions = true, groupId = null, cycleAnchor = null, cycleDays = null, workdays = false,
            wakeMethod = "NONE", wakeLevel = "MEDIUM", wakeTasks = 1, wakeReference = "", wakeSnooze = true, recheckMinutes = 0,
            gentleMinutes = 0, afterPresetId = null, afterReminder = "", afterReminderMinutes = 0, afterApp = "",
        ).toModel()
        assertEquals(com.dpsmpx.dpclock.model.Alarm("a", 7, 30, com.dpsmpx.dpclock.model.Repeat.fromBits(31), true, 1000), model)
    }

    @Test
    fun migration2To3KeepsAlarmsAndAddsGroupsTemplatesExceptions() = withDb { db ->
        SchemaFile.read(2).create(db)
        db.execSQL(
            "INSERT INTO alarm (id, hour, minute, repeatDays, enabled, createdAt, label, sound, soundTitle, vibrate, rampSeconds) " +
                "VALUES ('a', 6, 0, 31, 1, 1, 'Работа', 'builtin:dawn', '', 0, 30)",
        )
        db.execSQL(
            "INSERT INTO settings (id, snoozeMinutes, autoStopMinutes, alarmOrder, xiaomiHintsDone, signalStyle, volumeButtons) " +
                "VALUES (0, 10, 10, 'TIME_OF_DAY', 0, 'FULL_SCREEN', 'DISMISS')",
        )
        MIGRATION_2_3.migrate(db)

        val alarm = row(db, "SELECT * FROM alarm")
        assertEquals("Работа", alarm["label"])
        assertEquals("builtin:dawn", alarm["sound"])
        assertEquals("30", alarm["rampSeconds"])
        // Исключения по умолчанию учитываются; группы, графика и рабочих дней нет.
        assertEquals("1", alarm["honorExceptions"])
        assertEquals(null, alarm["groupId"])
        assertEquals(null, alarm["cycleAnchor"])
        assertEquals(null, alarm["cycleDays"])
        assertEquals("0", alarm["workdays"])

        val settings = row(db, "SELECT * FROM settings")
        assertEquals("DISMISS", settings["volumeButtons"])
        assertEquals("0", settings["holidaysRu"])

        for (table in listOf("alarm_group", "alarm_template", "exception_period")) {
            assertEquals(table, "0", row(db, "SELECT COUNT(*) AS n FROM `$table`")["n"])
        }
    }

    @Test
    fun migration3To4AddsWakeCheckOff() = withDb { db ->
        SchemaFile.read(3).create(db)
        db.execSQL(
            "INSERT INTO alarm (id, hour, minute, repeatDays, enabled, createdAt, label, sound, soundTitle, vibrate, rampSeconds, " +
                "honorExceptions, workdays) VALUES ('a', 6, 0, 0, 1, 1, '', '', '', 1, 0, 1, 1)",
        )
        db.execSQL("INSERT INTO alarm_occurrence (alarmId, scheduledAt, state) VALUES ('a', 100, 'SCHEDULED')")
        MIGRATION_3_4.migrate(db)

        val alarm = row(db, "SELECT * FROM alarm")
        assertEquals("1", alarm["workdays"])
        assertEquals("NONE", alarm["wakeMethod"])
        assertEquals("MEDIUM", alarm["wakeLevel"])
        assertEquals("1", alarm["wakeTasks"])
        assertEquals("", alarm["wakeReference"])
        assertEquals("1", alarm["wakeSnooze"])
        assertEquals("0", alarm["recheckMinutes"])
        val occurrence = row(db, "SELECT * FROM alarm_occurrence")
        assertEquals("SCHEDULED", occurrence["state"])
        assertEquals(null, occurrence["recheckAt"])
    }

    @Test
    fun migration4To5AddsSleepAndAfterAlarmOff() = withDb { db ->
        SchemaFile.read(4).create(db)
        db.execSQL(
            "INSERT INTO alarm (id, hour, minute, repeatDays, enabled, createdAt, label, sound, soundTitle, vibrate, rampSeconds, " +
                "honorExceptions, workdays, wakeMethod, wakeLevel, wakeTasks, wakeReference, wakeSnooze, recheckMinutes) " +
                "VALUES ('a', 6, 0, 0, 1, 1, '', '', '', 1, 0, 1, 0, 'MATH', 'HARD', 3, '', 0, 5)",
        )
        db.execSQL(
            "INSERT INTO settings (id, snoozeMinutes, autoStopMinutes, alarmOrder, xiaomiHintsDone, signalStyle, volumeButtons, holidaysRu) " +
                "VALUES (0, 10, 10, 'TIME_OF_DAY', 0, 'FULL_SCREEN', 'SNOOZE', 1)",
        )
        MIGRATION_4_5.migrate(db)

        val alarm = row(db, "SELECT * FROM alarm")
        assertEquals("MATH", alarm["wakeMethod"])
        assertEquals("5", alarm["recheckMinutes"])
        assertEquals("0", alarm["gentleMinutes"])
        assertEquals(null, alarm["afterPresetId"])
        assertEquals("", alarm["afterReminder"])
        assertEquals("", alarm["afterApp"])
        val settings = row(db, "SELECT * FROM settings")
        assertEquals("1", settings["holidaysRu"])
        assertEquals("OFF", settings["bedtimeMode"])
        assertEquals("480", settings["sleepMinutes"])
        assertEquals("127", settings["bedtimeDays"])
        assertEquals("15", settings["bedtimeLeadMinutes"])
    }

    @Test
    fun migration5To6KeepsJournalAndAddsTitle() = withDb { db ->
        SchemaFile.read(5).create(db)
        db.execSQL("INSERT INTO alarm_occurrence (alarmId, scheduledAt, state, snoozedUntil) VALUES ('a', 100, 'SNOOZED', 700)")
        db.execSQL(
            "INSERT INTO signal_event (id, source, sourceId, scheduledAt, firedAt, outcome) VALUES ('e', 'ALARM', 'a', 100, 103, 'SNOOZED')",
        )
        MIGRATION_5_6.migrate(db)

        val occurrence = row(db, "SELECT * FROM alarm_occurrence")
        assertEquals("700", occurrence["snoozedUntil"])
        assertEquals(null, occurrence["expectedAt"])
        val event = row(db, "SELECT * FROM signal_event")
        assertEquals("103", event["firedAt"])
        assertEquals("", event["title"])
    }

    @Test
    fun migration6To7AddsWorldCitiesAndWeatherNotSet() = withDb { db ->
        SchemaFile.read(6).create(db)
        db.execSQL(
            "INSERT INTO settings (id, snoozeMinutes, autoStopMinutes, alarmOrder, xiaomiHintsDone, signalStyle, volumeButtons, holidaysRu, " +
                "bedtimeMode, sleepMinutes, bedtimeHour, bedtimeMinute, bedtimeDays, bedtimeLeadMinutes) " +
                "VALUES (0, 5, NULL, 'NEXT', 1, 'FULL_SCREEN', 'SNOOZE', 0, 'FROM_ALARM', 450, 23, 0, 127, 15)",
        )
        MIGRATION_6_7.migrate(db)

        val settings = row(db, "SELECT * FROM settings")
        assertEquals("5", settings["snoozeMinutes"])
        assertEquals("FROM_ALARM", settings["bedtimeMode"])
        assertEquals("NOT_SET", settings["weatherMode"])
        assertEquals("", settings["weatherPlace"])
        assertEquals(null, settings["weatherLat"])
        assertEquals(emptyList<Map<String, String?>>(), rows(db, "SELECT * FROM world_city"))
    }

    @Test
    fun migration7To8KeepsSettingsAndAddsAppearanceDefaults() = withDb { db ->
        SchemaFile.read(7).create(db)
        db.execSQL(
            "INSERT INTO settings (id, snoozeMinutes, autoStopMinutes, alarmOrder, xiaomiHintsDone, signalStyle, volumeButtons, holidaysRu, " +
                "bedtimeMode, sleepMinutes, bedtimeHour, bedtimeMinute, bedtimeDays, bedtimeLeadMinutes, weatherMode, weatherPlace) " +
                "VALUES (0, 10, 10, 'TIME_OF_DAY', 0, 'FULL_SCREEN', 'SNOOZE', 0, 'OFF', 480, 23, 0, 127, 15, 'CITY', 'Тула')",
        )
        MIGRATION_7_8.migrate(db)

        val settings = row(db, "SELECT * FROM settings")
        assertEquals("CITY", settings["weatherMode"])
        assertEquals("Тула", settings["weatherPlace"])
        assertEquals("SYSTEM", settings["theme"])
        assertEquals(null, settings["seedColor"])
        assertEquals("CIRCLE", settings["clockStyle"])
        assertEquals("100", settings["clockSize"])
        assertEquals("NONE", settings["nightNoise"])
        assertEquals("NONE", settings["background"])
    }

    @Test
    fun migration8To9KeepsAppearanceAndTurnsHighContrastOff() = withDb { db ->
        SchemaFile.read(8).create(db)
        db.execSQL(
            "INSERT INTO settings (id, snoozeMinutes, autoStopMinutes, alarmOrder, xiaomiHintsDone, signalStyle, volumeButtons, holidaysRu, " +
                "bedtimeMode, sleepMinutes, bedtimeHour, bedtimeMinute, bedtimeDays, bedtimeLeadMinutes, weatherMode, weatherPlace, " +
                "theme, seedColor, clockStyle, clockFont, clockSize, nightNoise, background) " +
                "VALUES (0, 10, 10, 'TIME_OF_DAY', 0, 'FULL_SCREEN', 'SNOOZE', 0, 'OFF', 480, 23, 0, 127, 15, 'NOT_SET', '', " +
                "'BLACK', -12345, 'DIGITS', 'SEGMENT', 80, 'PINK', 'STARS')",
        )
        MIGRATION_8_9.migrate(db)

        val settings = row(db, "SELECT * FROM settings")
        assertEquals("BLACK", settings["theme"])
        assertEquals("-12345", settings["seedColor"])
        assertEquals("STARS", settings["background"])
        assertEquals("0", settings["highContrast"])
    }

    @Test
    fun migration9To10KeepsAlarmsAsOrdinary() = withDb { db ->
        SchemaFile.read(9).create(db)
        db.execSQL(
            "INSERT INTO alarm (id, hour, minute, repeatDays, enabled, createdAt, label, sound, soundTitle, vibrate, rampSeconds, " +
                "honorExceptions, workdays, wakeMethod, wakeLevel, wakeTasks, wakeReference, wakeSnooze, recheckMinutes, " +
                "gentleMinutes, afterReminder, afterReminderMinutes, afterApp) " +
                "VALUES ('a', 6, 30, 31, 1, 1, 'Подъём', '', '', 1, 0, 1, 0, 'NONE', 'MEDIUM', 1, '', 1, 0, 0, '', 0, '')",
        )
        MIGRATION_9_10.migrate(db)

        val alarm = row(db, "SELECT * FROM alarm")
        assertEquals("Подъём", alarm["label"])
        assertEquals("31", alarm["repeatDays"])
        assertEquals(null, alarm["calendarEventAt"])
    }

    @Test
    fun migration10To11KeepsTheJournalAndAddsAnEmptyActivityLog() = withDb { db ->
        SchemaFile.read(10).create(db)
        db.execSQL(
            "INSERT INTO signal_event (id, source, sourceId, scheduledAt, firedAt, outcome, title) " +
                "VALUES ('e', 'ALARM', 'a', 100, 130, 'DISMISSED', 'Подъём')",
        )
        MIGRATION_10_11.migrate(db)

        val event = row(db, "SELECT * FROM signal_event")
        assertEquals("130", event["firedAt"])
        assertEquals("Подъём", event["title"])
        assertEquals(null, event["endedAt"])
        assertEquals(emptyList<Map<String, String?>>(), rows(db, "SELECT * FROM activity_event"))
    }

    private data class Column(val type: String, val notNull: Boolean, val primaryKey: Int, val default: String?)

    private data class Table(val columns: Map<String, Column>, val indices: Set<String>, val foreignKeys: Set<String>)

    private fun describe(db: SQLiteConnection): Map<String, Table> {
        val tables = rows(db, "SELECT name FROM sqlite_master WHERE type = 'table'")
            .map { it.getValue("name")!! }
            .filterNot { it.startsWith("sqlite_") || it == "room_master_table" || it == "android_metadata" }
        assertTrue("таблиц нет", tables.isNotEmpty())
        return tables.associateWith { table ->
            val columns = rows(db, "PRAGMA table_info(`$table`)").associate {
                it.getValue("name")!! to Column(
                    type = it.getValue("type")!!.uppercase(),
                    notNull = it["notnull"] == "1",
                    primaryKey = it.getValue("pk")!!.toInt(),
                    default = it["dflt_value"],
                )
            }
            val indices = rows(db, "PRAGMA index_list(`$table`)")
                .filter { it["origin"] == "c" }
                .map { index ->
                    val name = index.getValue("name")!!
                    val cols = rows(db, "PRAGMA index_info(`$name`)").joinToString(",") { it.getValue("name")!! }
                    "$name unique=${index["unique"]} ($cols)"
                }
                .toSet()
            val foreignKeys = rows(db, "PRAGMA foreign_key_list(`$table`)")
                .map { "${it["from"]}→${it["table"]}.${it["to"]} update=${it["on_update"]} delete=${it["on_delete"]}" }
                .toSet()
            Table(columns, indices, foreignKeys)
        }
    }

    private fun row(db: SQLiteConnection, sql: String): Map<String, String?> = rows(db, sql).single()

    private fun rows(db: SQLiteConnection, sql: String): List<Map<String, String?>> = db.prepare(sql).use { statement ->
        buildList {
            while (statement.step()) {
                add((0 until statement.getColumnCount()).associate { i ->
                    statement.getColumnName(i) to if (statement.isNull(i)) null else statement.getText(i)
                })
            }
        }
    }
}
