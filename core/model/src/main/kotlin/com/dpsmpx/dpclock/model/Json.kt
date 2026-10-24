package com.dpsmpx.dpclock.model

/**
 * JSON без библиотек (D-018): файл экспорта и схемы Room в тесте миграций.
 * Объект — `Map<String, Any?>` в порядке ключей, массив — `List<Any?>`, строка,
 * число — `Long` или, с дробной частью и степенью, `Double`; `true`, `false`, `null`.
 */
object Json {
    /** Глубже не бывает в файлах DP_Clock; защита от переполнения стека чужим файлом. */
    private const val MAX_DEPTH = 64

    /** Разбор всего текста; ошибка — [IllegalArgumentException] с местом в тексте. */
    fun parse(text: String): Any? = try {
        Parser(text).run { value(0).also { space(); require(at == text.length) { "лишнее после JSON на $at" } } }
    } catch (_: IndexOutOfBoundsException) {
        throw IllegalArgumentException("JSON обрывается")
    }

    /** Запись с отступом в 2 пробела; массив из одних чисел и строк — в одну строку. Кириллица — как есть. */
    fun write(value: Any?): String = StringBuilder().also { write(it, value, "") }.append('\n').toString()

    private fun write(out: StringBuilder, value: Any?, indent: String) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Double -> {
                require(value.isFinite()) { "число $value" }
                out.append(value.toString())
            }
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    out.append("{}")
                    return
                }
                val inner = "$indent  "
                out.append("{\n")
                value.entries.forEachIndexed { index, (key, item) ->
                    out.append(inner)
                    quote(out, key as String)
                    out.append(": ")
                    write(out, item, inner)
                    if (index < value.size - 1) out.append(',')
                    out.append('\n')
                }
                out.append(indent).append('}')
            }
            is List<*> -> {
                if (value.none { it is Map<*, *> || it is List<*> }) {
                    out.append('[')
                    value.forEachIndexed { index, item ->
                        if (index > 0) out.append(", ")
                        write(out, item, indent)
                    }
                    out.append(']')
                    return
                }
                val inner = "$indent  "
                out.append("[\n")
                value.forEachIndexed { index, item ->
                    out.append(inner)
                    write(out, item, inner)
                    if (index < value.size - 1) out.append(',')
                    out.append('\n')
                }
                out.append(indent).append(']')
            }
            else -> throw IllegalArgumentException("не JSON: ${value::class.simpleName}")
        }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        var at = 0

        fun value(depth: Int): Any? {
            require(depth < MAX_DEPTH) { "слишком глубоко на $at" }
            space()
            return when (val c = text[at]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else throw IllegalArgumentException("символ «$c» на $at")
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            at++
            space()
            if (text[at] == '}') return map.also { at++ }
            while (true) {
                space()
                val key = string()
                space()
                expect(':')
                map[key] = value(depth + 1)
                space()
                if (text[at] == ',') at++ else return map.also { expect('}') }
            }
        }

        private fun array(depth: Int): List<Any?> {
            val list = mutableListOf<Any?>()
            at++
            space()
            if (text[at] == ']') return list.also { at++ }
            while (true) {
                list += value(depth + 1)
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
                        'u' -> {
                            val code = text.substring(at, at + 4).toIntOrNull(16) ?: throw IllegalArgumentException("\\u на $at")
                            out.append(code.toChar())
                            at += 4
                        }
                        '"', '\\', '/' -> out.append(e)
                        else -> throw IllegalArgumentException("\\$e на $at")
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Any {
            val start = at
            if (text[at] == '-') at++
            digits()
            var whole = true
            if (at < text.length && text[at] == '.') {
                whole = false
                at++
                digits()
            }
            if (at < text.length && (text[at] == 'e' || text[at] == 'E')) {
                whole = false
                at++
                if (text[at] == '+' || text[at] == '-') at++
                digits()
            }
            val raw = text.substring(start, at)
            return (if (whole) raw.toLongOrNull() else null) ?: raw.toDouble()
        }

        private fun digits() {
            val start = at
            while (at < text.length && text[at] in '0'..'9') at++
            require(at > start) { "нет цифр на $at" }
        }

        private fun word(word: String, value: Any?): Any? {
            require(text.startsWith(word, at)) { "ожидалось $word на $at" }
            at += word.length
            return value
        }

        private fun expect(c: Char) {
            require(text[at] == c) { "ожидалось «$c» на $at" }
            at++
        }

        fun space() {
            while (at < text.length && text[at].isWhitespace()) at++
        }
    }
}
