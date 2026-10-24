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
        ).toModel()
        assertEquals(com.dpsmpx.dpclock.model.Alarm("a", 7, 30, com.dpsmpx.dpclock.model.Repeat.fromBits(31), true, 1000), model)
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
