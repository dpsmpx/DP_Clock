package com.dpsmpx.dpclock.model

/** Тема (D-021, п. 2; выбор автора — все четыре). */
enum class ThemeMode {
    /** Как в системе; по умолчанию. */
    SYSTEM,
    LIGHT,
    DARK,

    /** Тёмная на чистом чёрном — для AMOLED. */
    BLACK,
}

/** Стиль часов в разделе «Часы» и в ночных часах (D-021, п. 4; выбор автора — все четыре). */
enum class ClockStyle {
    /** Цифры в круге — как с 0.0.0; по умолчанию. */
    CIRCLE,

    /** Со стрелками: числа, метки, плавная секундная. */
    ANALOG_CLASSIC,

    /** Со стрелками: тонкие стрелки и метки без чисел. */
    ANALOG_MINIMAL,

    /** Крупные цифры во всю ширину; начертание и размер — свои. */
    DIGITS,
}

/** Начертание крупных цифр ([ClockStyle.DIGITS]). */
enum class ClockFont { REGULAR, THIN, BOLD, SEGMENT }

/** Фон раздела «Часы» (D-021, п. 6; выбор автора — картинка, анимированные, живые). */
enum class ClockBackground {
    NONE,

    /** Своя картинка из галереи. */
    IMAGE,

    /** Анимированные: небо по времени суток, волны, звёзды, погода вашего города. */
    SKY,
    WAVES,
    STARS,
    WEATHER,

    /** Живые — отвечают на касание и наклон: частицы, небо. */
    PARTICLES,
    LIVE_SKY,

    /**
     * Эффекты-обои (D-024): «Созвездие» из AIM_Trainer_pas, гиперкуб, полярные
     * часы, песочные часы, механизм («дождь цифр» убран в 0.24.0, D-036: в базе
     * и файлах он читается как «Нет»). Они же — обои Android,
     * фон ночных часов и фон приложения.
     */
    CONSTELLATION,
    HYPERCUBE,
    POLAR_CLOCK,
    HOURGLASS,
    MECHANISM,
}

/** Шум в ночных часах (D-021, п. 5; дополнение автора). */
enum class NoiseKind { NONE, WHITE, PINK, BROWN }

/** Оформление (D-021). [seedColor] — цвет приложения ARGB; null — из обоев. */
data class Appearance(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val seedColor: Int? = null,
    val clockStyle: ClockStyle = ClockStyle.CIRCLE,
    val clockFont: ClockFont = ClockFont.REGULAR,
    /** Размер крупных цифр, % от ширины экрана. */
    val clockSize: Int = MAX_SIZE,
    val nightNoise: NoiseKind = NoiseKind.NONE,
    /**
     * Фон приложения (D-027): один на все разделы — в «Часах» ярко, за списками
     * едва заметно, в ночных часах тёмно-красным. До 0.15.0 — фон только «Часов».
     */
    val background: ClockBackground = ClockBackground.NONE,
    /** «Высокий контраст» (D-022, п. 5): текст и границы темнее; включается и системной настройкой Android 14+. */
    val highContrast: Boolean = false,
    /** Что показывают обои Android DP Clock (D-024, п. 2). */
    val wallpaper: ClockBackground = ClockBackground.CONSTELLATION,
) {
    init {
        require(clockSize in MIN_SIZE..MAX_SIZE) { "размер $clockSize" }
    }

    companion object {
        const val MIN_SIZE = 50
        const val MAX_SIZE = 100
        val SIZE_CHOICES = listOf(50, 60, 70, 80, 90, 100)
    }
}

/**
 * Семисегментное «табло» (D-021, п. 4): какие сегменты горят у цифры. Биты:
 * a — верх (0), b — правый верх (1), c — правый низ (2), d — низ (3), e — левый
 * низ (4), f — левый верх (5), g — середина (6).
 */
object SevenSegment {
    private val DIGITS = intArrayOf(
        0b0111111, // 0: a b c d e f
        0b0000110, // 1: b c
        0b1011011, // 2: a b d e g
        0b1001111, // 3: a b c d g
        0b1100110, // 4: b c f g
        0b1101101, // 5: a c d f g
        0b1111101, // 6: a c d e f g
        0b0000111, // 7: a b c
        0b1111111, // 8: все
        0b1101111, // 9: a b c d f g
    )

    /** Сегменты цифры [char]; не цифра — ни одного. */
    fun segments(char: Char): Int = if (char in '0'..'9') DIGITS[char - '0'] else 0

    fun lit(char: Char, segment: Int): Boolean = segments(char) and (1 shl segment) != 0
}
