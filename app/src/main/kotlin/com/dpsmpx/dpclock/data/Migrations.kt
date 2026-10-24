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
