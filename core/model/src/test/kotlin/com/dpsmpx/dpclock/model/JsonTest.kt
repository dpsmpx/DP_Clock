package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonTest {
    @Test
    fun writesAndReadsBack() {
        val value = mapOf(
            "text" to "Кавычка \" и \\ слэш\nперевод\tтаб\u0001",
            "long" to 1_729_000_000_000L,
            "negative" to -5L,
            "double" to 2.5,
            "flags" to listOf(true, false, null),
            "empty" to mapOf<String, Any?>(),
            "none" to emptyList<Any?>(),
            "nested" to listOf(mapOf("a" to 1L), mapOf("b" to listOf("x", "y"))),
        )
        assertEquals(value, Json.parse(Json.write(value)))
    }

    @Test
    fun layoutIsReadable() {
        assertEquals(
            "{\n  \"days\": [\"MONDAY\", \"FRIDAY\"],\n  \"list\": [\n    {\n      \"a\": 1\n    }\n  ],\n  \"empty\": {}\n}\n",
            Json.write(mapOf("days" to listOf("MONDAY", "FRIDAY"), "list" to listOf(mapOf("a" to 1)), "empty" to emptyMap<String, Any?>())),
        )
    }

    @Test
    fun readsStandardJson() {
        assertEquals(
            mapOf("a" to listOf(1L, -2L, 3.5, 1.0e3), "b" to "é/€😀", "c" to null),
            Json.parse(""" { "a" : [ 1, -2, 3.5, 1e3 ], "b": "\u00e9\/\u20ac\ud83d\ude00", "c": null } """),
        )
    }

    @Test
    fun rejectsBrokenText() {
        for (text in listOf("", "{", "[1,]", "{\"a\" 1}", "tru", "01x", "\"\\q\"", "{} {}", "-", "1.", "[".repeat(100) + "]".repeat(100))) {
            assertThrows(text, IllegalArgumentException::class.java) { Json.parse(text) }
        }
    }
}
