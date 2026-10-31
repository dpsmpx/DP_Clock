package com.dpsmpx.dpclock.platform

import android.content.Context
import android.graphics.PointF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.wallpaper.WallpaperService
import android.text.format.DateFormat
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.model.Appearance
import com.dpsmpx.dpclock.model.ClockBackground
import com.dpsmpx.dpclock.model.Palette
import com.dpsmpx.dpclock.ui.SCENE_BACKGROUND
import com.dpsmpx.dpclock.ui.ScenePalette
import com.dpsmpx.dpclock.ui.SceneInput
import com.dpsmpx.dpclock.ui.SceneRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Живые обои DP Clock (D-024, п. 2): эффект из настроек на рабочем столе и
 * экране блокировки. Рисуется, только пока обои видны, не чаще 30 кадров в
 * секунду; в экономии заряда и с выключенной анимацией — раз в минуту.
 */
class ClockWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = SceneEngine()

    private inner class SceneEngine : Engine(), Choreographer.FrameCallback, SensorEventListener {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val handler = Handler(Looper.getMainLooper())
        private val input = SceneInput()
        private var renderer: SceneRenderer? = null
        private var palette = ScenePalette(SCENE_BACKGROUND, android.graphics.Color.WHITE, android.graphics.Color.WHITE)
        private var visible = false
        private var width = 0
        private var height = 0
        private var lastFrame = 0L
        private val started = SystemClock.elapsedRealtime()
        private var lastTouch: PointF? = null
        private val minuteTick = Runnable { tickStill() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            val context = applicationContext
            scope.launch {
                DpClockApp.graph(context).repository.settings.collect { settings ->
                    val kind = settings.appearance.wallpaper
                    renderer = SceneRenderer.create(kind) ?: SceneRenderer.create(ClockBackground.CONSTELLATION)
                    palette = wallpaperPalette(context, settings.appearance)
                    restart()
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            restart()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.width = width
            this.height = height
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            stop()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stop()
            scope.cancel()
            super.onDestroy()
        }

        override fun onTouchEvent(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val point = PointF(event.x, event.y)
                    lastTouch?.let {
                        input.dragX += point.x - it.x
                        input.dragY += point.y - it.y
                    }
                    lastTouch = point
                    input.touch = point
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    lastTouch = null
                    input.touch = null
                }
            }
        }

        /** Анимация выключена системой или экономия заряда — кадр раз в минуту. */
        private fun still(): Boolean {
            val power = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
            val animations = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
            return power || !animations
        }

        private fun restart() {
            stop()
            if (!visible) return
            input.is24Hour = DateFormat.is24HourFormat(applicationContext)
            drawFrame()
            if (still()) {
                scheduleMinute()
            } else {
                if (renderer?.usesTilt == true) {
                    val sensors = getSystemService(SensorManager::class.java)
                    val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                    if (sensor != null) sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        private fun stop() {
            Choreographer.getInstance().removeFrameCallback(this)
            handler.removeCallbacks(minuteTick)
            getSystemService(SensorManager::class.java)?.unregisterListener(this)
        }

        private fun scheduleMinute() {
            val now = System.currentTimeMillis()
            handler.postDelayed(minuteTick, 60_000L - now % 60_000L + 50L)
        }

        private fun tickStill() {
            if (!visible) return
            drawFrame()
            scheduleMinute()
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!visible) return
            // Не чаще 30 кадров в секунду.
            if (frameTimeNanos - lastFrame >= FRAME_NANOS) {
                lastFrame = frameTimeNanos
                drawFrame()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }

        private fun drawFrame() {
            val scene = renderer ?: return
            if (width == 0 || height == 0) return
            val holder = surfaceHolder
            val canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: return
            try {
                val t = (SystemClock.elapsedRealtime() - started) / 1000f
                scene.draw(canvas, width, height, t, System.currentTimeMillis(), input, palette)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            // Как «Частицы» в приложении: плавно, без дрожи.
            input.tiltX += ((-event.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f) - input.tiltX) * 0.15f
            input.tiltY += ((event.values[1] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f) - input.tiltY) * 0.15f
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private companion object {
        const val FRAME_NANOS = 33_000_000L
    }
}

/** Цвет обоев: светлый тон своего цвета приложения или цвета обоев Android — на тёмном фоне. */
fun wallpaperPalette(context: Context, appearance: Appearance): ScenePalette {
    val seed = appearance.seedColor
    val accent = if (seed != null) Palette.scheme(seed, dark = true).primary else context.getColor(android.R.color.system_accent1_200)
    return ScenePalette(SCENE_BACKGROUND, accent, android.graphics.Color.WHITE)
}
