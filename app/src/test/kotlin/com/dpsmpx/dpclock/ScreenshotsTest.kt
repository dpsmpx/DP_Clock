package com.dpsmpx.dpclock

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.media.AudioManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.dpsmpx.dpclock.model.Repeat
import com.dpsmpx.dpclock.model.TimerPreset
import com.dpsmpx.dpclock.model.TimerProgram
import com.dpsmpx.dpclock.model.TimerStep
import com.dpsmpx.dpclock.model.WeatherMode
import com.dpsmpx.dpclock.model.WeatherSettings
import com.dpsmpx.dpclock.ui.AppIcons
import com.dpsmpx.dpclock.ui.FullScreenTime
import com.dpsmpx.dpclock.ui.NewAlarmDialog
import com.dpsmpx.dpclock.ui.OnboardingContent
import com.dpsmpx.dpclock.ui.SettingsScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowNotificationManager
import java.io.File
import java.time.DayOfWeek
import java.time.Duration

/**
 * Снимки экранов на JVM (D-029): настоящие экраны приложения с примерными данными —
 * в `app/build/screenshots`. Запуск — `./gradlew :app:testDebugUnitTest -Pscreenshots
 * --tests '*ScreenshotsTest*'`; в обычной проверке пропускаются. Экран — как у
 * Redmi Note 13, язык — русский.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "ru-rRU-w393dp-h873dp-xxhdpi", shadows = [FullScreenAllowed::class])
class ScreenshotsTest {
    private val app: DpClockApp get() = RuntimeEnvironment.getApplication() as DpClockApp
    private val dir = File(System.getProperty("dpclock.screenshots.dir") ?: "build/screenshots")

    @Before
    fun setUp() {
        Assume.assumeTrue(System.getProperty("dpclock.screenshots") == "true")
        // Кадр — каждые 16 мс, как на телефоне: иначе анимации крутятся без остановки.
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        // Разрешения выданы, громкость будильника на месте — плашки надёжности нет, как на настроенном телефоне.
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val audio = app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        // Отметка «настроил Xiaomi» — подсказки для HyperOS на снимках не нужны.
        // Погода без сети не загрузится; её анимированное небо Robolectric не отпускает — без погоды.
        runBlocking {
            DpClockApp.graph(app).repository.updateSettings {
                it.copy(xiaomiHintsDone = true, onboardingDone = true, weather = WeatherSettings(WeatherMode.OFF))
            }
        }
    }

    @Test
    fun sections() {
        seed()
        for (section in Section.entries) {
            val controller = Robolectric.buildActivity(MainActivity::class.java, MainActivity.intent(app, section)).setup()
            save(controller.get(), "section-" + section.name.lowercase())
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun settings() {
        shoot("settings") { DpClockTheme { SettingsScreen(onBack = {}) } }
        shoot("settings-appearance") { DpClockTheme { SettingsScreen(onBack = {}, initialSection = R.string.settings_appearance) } }
    }

    /** Окна 0.16.0 (D-029): новый будильник, первый запуск, таймер во весь экран. */
    @Test
    fun windows() {
        shoot("new-alarm") { DpClockTheme { NewAlarmDialog(onDismiss = {}, onCreate = { _, _, _, _ -> }) } }
        shoot("onboarding") { DpClockTheme { OnboardingContent(onXiaomiDone = {}, onDone = {}) } }
        shoot("timer-full-screen") {
            DpClockTheme {
                FullScreenTime(title = "Паста", time = "7:42", onClose = {}) {
                    FilledIconButton(onClick = {}) { Icon(AppIcons.Pause, contentDescription = null) }
                    FilledTonalButton(onClick = {}) { Text("+1 мин") }
                }
            }
        }
    }

    /** Экран из одного композиционного блока — в простой активности. */
    private fun shoot(name: String, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent(content = content)
        save(controller.get(), name)
        controller.pause().stop().destroy()
    }

    /** Будильники, пресеты и идущий таймер — как у человека, который приложением пользуется. */
    private fun seed() = runBlocking {
        val repository = DpClockApp.graph(app).repository
        val workdays = DayOfWeek.entries.take(5).toSet()
        repository.addAlarm(7, 0)
        val first = repository.surfaceData().alarms.first()
        repository.saveAlarm(first.copy(label = "Подъём", repeat = Repeat.Weekly(workdays)))
        repository.addAlarm(9, 30)
        val second = repository.surfaceData().alarms.first { it.hour == 9 }
        repository.saveAlarm(second.copy(label = "Выходные", repeat = Repeat.Weekly(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)), enabled = false))
        repository.addAlarm(14, 15)
        repository.savePreset(TimerPreset("p1", "Чай", TimerProgram.single(4 * 60_000L), 1L))
        repository.savePreset(
            TimerPreset(
                "p2",
                "Планка",
                TimerProgram(listOf(TimerStep(45_000L, "Работа", fullSignal = false), TimerStep(15_000L, "Отдых", fullSignal = false)), cycles = 4),
                2L,
            ),
        )
        repository.startTimer(TimerProgram.single(10 * 60_000L), "Паста", null)
    }

    /** Дождаться данных из базы и кадров, затем нарисовать окно в PNG. */
    private fun save(activity: Activity, name: String) {
        repeat(SETTLE_STEPS) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(15)
        }
        val view = activity.window.decorView
        val bitmap = createBitmap(view.width, view.height)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        // Открытое окно — поверх затемнённого экрана, как на телефоне.
        ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }?.window?.decorView?.let { dialog ->
            canvas.drawColor(android.graphics.Color.argb(0x66, 0, 0, 0))
            canvas.withTranslation((view.width - dialog.width) / 2f, (view.height - dialog.height) / 2f) { dialog.draw(this) }
        }
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val SETTLE_STEPS = 30
    }
}

/** Полноэкранный сигнал разрешён, как на настроенном телефоне: у Robolectric своего переключателя нет. */
@Implements(NotificationManager::class)
class FullScreenAllowed : ShadowNotificationManager() {
    @Implementation
    fun canUseFullScreenIntent(): Boolean = true
}
