package com.dpsmpx.dpclock.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
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
            val root = Json(file.readText()).parse() as Map<String, Any?>
            val database = root.getValue("database") as Map<String, Any?>
            val statements = mutableListOf<String>()
            for (entity in database.getValue("entities") as List<Map<String, Any?>>) {
                val table = entity.getValue("tableName") as String
                statements += (entity.getValue("createSql") as String).replace("\${TABLE_NAME}", table)
                for (index in entity["indices"] as List<Map<String, Any?>>? ?: emptyList()) {
                    statements += (index.getValue("createSql") as String).replace("\${TABLE_NAME}", table)
                }
            }
            return SchemaFile(database.getValue("version") as Int, statements)
        }
    }
}

/** Разбор JSON без библиотек: объекты, массивы, строки, целые числа, true, false, null. */
private class Json(private val text: String) {
    private var at = 0

    fun parse(): Any? = value().also { space(); check(at == text.length) { "лишнее после JSON на $at" } }

    private fun value(): Any? {
        space()
        return when (val c = text[at]) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> word("true", true)
            'f' -> word("false", false)
            'n' -> word("null", null)
            else -> if (c == '-' || c.isDigit()) number() else error("символ «$c» на $at")
        }
    }

    private fun obj(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        at++
        space()
        if (text[at] == '}') return map.also { at++ }
        while (true) {
            space()
            val key = string()
            space()
            expect(':')
            map[key] = value()
            space()
            if (text[at] == ',') at++ else return map.also { expect('}') }
        }
    }

    private fun array(): List<Any?> {
        val list = mutableListOf<Any?>()
        at++
        space()
        if (text[at] == ']') return list.also { at++ }
        while (true) {
            list += value()
            space()
            if (text[at] == ',') at++ else return list.also { expect(']') }
        }
    }

    private fun string(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            when (val c = text[at++]) {
                '"' -> return out.toString()
                '\\' -> when (val e = text[at++]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> out.append(text.substring(at, at + 4).toInt(16).toChar()).also { at += 4 }
                    else -> out.append(e)
                }
                else -> out.append(c)
            }
        }
    }

    private fun number(): Int {
        val start = at
        if (text[at] == '-') at++
        while (at < text.length && text[at].isDigit()) at++
        return text.substring(start, at).toInt()
    }

    private fun word(word: String, value: Any?): Any? {
        check(text.startsWith(word, at)) { "ожидалось $word на $at" }
        at += word.length
        return value
    }

    private fun expect(c: Char) {
        check(text[at] == c) { "ожидалось «$c» на $at" }
        at++
    }

    private fun space() {
        while (at < text.length && text[at].isWhitespace()) at++
    }
}
