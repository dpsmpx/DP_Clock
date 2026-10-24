package com.dpsmpx.dpclock.model

/**
 * Звук будильника (D-013, п. 2): мелодия телефона, файл человека или
 * собственный звук DP_Clock, синтезированный кодом.
 */
sealed interface AlarmSound {
    /** Мелодия будильника телефона по умолчанию — как в версии 0.1.0. */
    data object Default : AlarmSound

    /** Без звука: только вибрация. */
    data object Silent : AlarmSound

    /** Собственный звук DP_Clock; [id] — из каталога в приложении. */
    data class Builtin(val id: String) : AlarmSound

    /** Мелодия телефона: адрес из системного списка мелодий. */
    data class Ringtone(val uri: String, override val title: String) : AlarmSound

    /** Аудиофайл, выбранный человеком; доступ к нему выдан навсегда. */
    data class File(val uri: String, override val title: String) : AlarmSound

    /** Строка для базы; название мелодии и файла хранится рядом, отдельно. */
    fun encode(): String = when (this) {
        Default -> ""
        Silent -> SILENT
        is Builtin -> BUILTIN + id
        is Ringtone -> RINGTONE + uri
        is File -> FILE + uri
    }

    /** Название мелодии телефона или файла; у остальных пусто — его даёт интерфейс. */
    val title: String get() = ""

    companion object {
        private const val SILENT = "silent"
        private const val BUILTIN = "builtin:"
        private const val RINGTONE = "ringtone:"
        private const val FILE = "file:"

        /** Неизвестная строка — мелодия по умолчанию: будильник всё равно прозвонит. */
        fun decode(code: String, title: String): AlarmSound = when {
            code == SILENT -> Silent
            code.startsWith(BUILTIN) -> Builtin(code.removePrefix(BUILTIN))
            code.startsWith(RINGTONE) -> Ringtone(code.removePrefix(RINGTONE), title)
            code.startsWith(FILE) -> File(code.removePrefix(FILE), title)
            else -> Default
        }
    }
}
