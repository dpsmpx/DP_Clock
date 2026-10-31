package com.dpsmpx.dpclock

import android.app.Application
import android.content.Context
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import com.dpsmpx.dpclock.data.CalendarStore
import com.dpsmpx.dpclock.data.ClockDatabase
import com.dpsmpx.dpclock.data.ClockRepository
import com.dpsmpx.dpclock.data.WeatherStore
import com.dpsmpx.dpclock.platform.Clocks
import com.dpsmpx.dpclock.platform.Notifier
import com.dpsmpx.dpclock.platform.Scheduler
import com.dpsmpx.dpclock.platform.Surfaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Разделы нижней панели слева направо (D-010, п. 2). */
enum class Section { ALARMS, TIMERS, STOPWATCH, CLOCK, WORLD }

/** Общие объекты процесса. Работают и до первой разблокировки: база — в хранилище устройства. */
class AppGraph(context: Context) {
    val scheduler = Scheduler(context)
    val notifier = Notifier(context)

    /** Календарь телефона (D-023): выбор календарей и даты исключений. */
    val calendar = CalendarStore(context)
    val repository = ClockRepository(context, ClockDatabase.open(context), scheduler, notifier, calendar) { Surfaces.changed(context) }

    /** Погода нужна только экрану «Мировые часы» (D-019). */
    val weather by lazy { WeatherStore(context, repository) }
}

class DpClockApp : Application(), CameraXConfig.Provider {
    override fun onCreate() {
        super.onCreate()
        Clocks.init(this)
        graph(this).notifier.createChannels()
    }

    /** Настройки камеры — отсюда, а не из метаданных службы: так камера открывается и до первой разблокировки. */
    override fun getCameraXConfig(): CameraXConfig = Camera2Config.defaultConfig()

    companion object {
        /** Работа, которая должна закончиться, даже если экран закрыли. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Volatile
        private var graph: AppGraph? = null

        fun graph(context: Context): AppGraph =
            graph ?: synchronized(this) { graph ?: AppGraph(context.applicationContext).also { graph = it } }
    }
}

/** Изменение данных из интерфейса: идёт в области процесса и не обрывается вместе с экраном. */
suspend fun <T> persist(block: suspend () -> T): T = DpClockApp.scope.async { block() }.await()

/** То же без ожидания результата. */
fun persistLater(block: suspend () -> Unit) {
    DpClockApp.scope.launch { block() }
}
