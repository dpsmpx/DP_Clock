package com.dpsmpx.dpclock.platform

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.dpsmpx.dpclock.model.Moment

/** Часы устройства для отсчёта длительностей (DATA_MODEL.md, п. 1). */
object Clocks {
    /** Номер загрузки не меняется, пока жив процесс: читается один раз. */
    @Volatile
    private var bootCount = 0

    fun init(context: Context) {
        bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    }

    fun moment(): Moment = Moment(SystemClock.elapsedRealtime(), bootCount, System.currentTimeMillis())
}
