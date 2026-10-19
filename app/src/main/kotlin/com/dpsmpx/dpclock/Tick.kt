package com.dpsmpx.dpclock

/** Сколько миллисекунд до начала следующей секунды: часы обновляются ровно на её границе. */
internal fun millisToNextSecond(nowMillis: Long): Long = 1000L - Math.floorMod(nowMillis, 1000L)
