package com.dpsmpx.dpclock.model

/**
 * Момент для отсчёта длительностей (DATA_MODEL.md, п. 1): монотонные часы
 * устройства в пределах одной загрузки и момент UTC на случай перезагрузки.
 */
data class Moment(
    /** `elapsedRealtime`, мс от загрузки. */
    val elapsed: Long,
    /** Номер загрузки устройства. */
    val bootCount: Int,
    /** Мс UTC. */
    val utc: Long,
) {
    operator fun plus(millis: Long): Moment = Moment(elapsed + millis, bootCount, utc + millis)

    /**
     * Сколько мс от [now] до этого момента (отрицательное — уже прошло). В той же
     * загрузке — по монотонным часам, им не страшен перевод времени; после
     * перезагрузки — по UTC.
     */
    fun millisAfter(now: Moment): Long =
        if (bootCount == now.bootCount) elapsed - now.elapsed else utc - now.utc

    /** Тот же момент в часах текущей загрузки [now]: после перезагрузки — пересчёт через UTC. */
    fun rebasedTo(now: Moment): Moment =
        if (bootCount == now.bootCount) this else Moment(now.elapsed + (utc - now.utc), now.bootCount, utc)
}
