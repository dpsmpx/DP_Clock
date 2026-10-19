package com.dpsmpx.dpclock.model

enum class StopwatchState { RESET, RUNNING, PAUSED }

/** Секундомер, один на приложение (DATA_MODEL.md, п. 6). */
data class Stopwatch(
    val state: StopwatchState = StopwatchState.RESET,
    /** Старт текущего отрезка, пока идёт. */
    val startedAt: Moment? = null,
    /** Время прошлых отрезков. */
    val accumulatedMillis: Long = 0L,
    /** Общее время на отметке каждого круга. */
    val laps: List<Long> = emptyList(),
) {
    fun elapsedAt(now: Moment): Long = when (state) {
        StopwatchState.RUNNING -> accumulatedMillis + (-startedAt!!.millisAfter(now)).coerceAtLeast(0L)
        else -> accumulatedMillis
    }

    fun start(now: Moment): Stopwatch =
        if (state == StopwatchState.RUNNING) this else copy(state = StopwatchState.RUNNING, startedAt = now)

    fun pause(now: Moment): Stopwatch =
        if (state != StopwatchState.RUNNING) this
        else copy(state = StopwatchState.PAUSED, startedAt = null, accumulatedMillis = elapsedAt(now))

    fun lap(now: Moment): Stopwatch =
        if (state != StopwatchState.RUNNING) this else copy(laps = laps + elapsedAt(now))

    fun reset(): Stopwatch = Stopwatch()

    /** Строки кругов, последний сверху; лучший и худший отмечены, когда кругов больше одного. */
    fun lapRows(): List<LapRow> {
        val splits = laps.mapIndexed { i, total -> total - (if (i == 0) 0L else laps[i - 1]) }
        val best = if (splits.size > 1) splits.min() else null
        val worst = if (splits.size > 1) splits.max() else null
        return splits.mapIndexed { i, split ->
            LapRow(
                number = i + 1,
                lapMillis = split,
                totalMillis = laps[i],
                isBest = split == best && best != worst,
                isWorst = split == worst && best != worst,
            )
        }.reversed()
    }

    /** Круги — в строку для базы: общие времена через запятую. */
    fun encodeLaps(): String = laps.joinToString(",")

    companion object {
        fun decodeLaps(text: String): List<Long> =
            if (text.isEmpty()) emptyList() else text.split(',').map { it.toLong() }
    }
}

data class LapRow(
    val number: Int,
    val lapMillis: Long,
    val totalMillis: Long,
    val isBest: Boolean,
    val isWorst: Boolean,
)
