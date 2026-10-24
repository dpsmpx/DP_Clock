package com.dpsmpx.dpclock.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.dpsmpx.dpclock.model.Json
import java.io.File

/**
 * Выгруженная Room схема одной версии (`app/schemas/…/N.json`): только то,
 * что нужно тесту миграций, — SQL создания таблиц и индексов.
 */
class SchemaFile(val version: Int, private val statements: List<String>) {
    fun create(connection: SQLiteConnection) {
        for (sql in statements) connection.execSQL(sql)
    }

    companion object {
        val dir = File("schemas/${ClockDatabase::class.java.name}")

        fun versions(): List<Int> =
            dir.listFiles().orEmpty().mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }.sorted()

        @Suppress("UNCHECKED_CAST")
        fun read(version: Int): SchemaFile {
            val file = File(dir, "$version.json")
            check(file.exists()) { "нет схемы ${file.absolutePath}: тесты идут из каталога модуля app" }
            val root = Json.parse(file.readText()) as Map<String, Any?>
            val database = root.getValue("database") as Map<String, Any?>
            val statements = mutableListOf<String>()
            for (entity in database.getValue("entities") as List<Map<String, Any?>>) {
                val table = entity.getValue("tableName") as String
                statements += (entity.getValue("createSql") as String).replace("\${TABLE_NAME}", table)
                for (index in entity["indices"] as List<Map<String, Any?>>? ?: emptyList()) {
                    statements += (index.getValue("createSql") as String).replace("\${TABLE_NAME}", table)
                }
            }
            return SchemaFile((database.getValue("version") as Long).toInt(), statements)
        }
    }
}
