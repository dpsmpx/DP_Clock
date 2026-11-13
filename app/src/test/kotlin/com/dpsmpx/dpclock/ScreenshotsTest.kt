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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.dpsmpx.dpclock.model.Alarm
import com.dpsmpx.dpclock.model.Backup
import com.dpsmpx.dpclock.model.Repeat
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.SyncConflict
import com.dpsmpx.dpclock.model.SyncItems
import com.dpsmpx.dpclock.model.Task
import com.dpsmpx.dpclock.model.TaskLevel
import com.dpsmpx.dpclock.model.TaskPrefs
import com.dpsmpx.dpclock.model.TaskRepeat
import com.dpsmpx.dpclock.model.TimerPreset
import com.dpsmpx.dpclock.model.TimerProgram
import com.dpsmpx.dpclock.model.TimerStep
import com.dpsmpx.dpclock.model.WeatherMode
import com.dpsmpx.dpclock.model.WeatherSettings
import com.dpsmpx.dpclock.ui.AppIcons
import com.dpsmpx.dpclock.ui.FullScreenTime
import com.dpsmpx.dpclock.ui.NewAlarmDialog
import com.dpsmpx.dpclock.ui.OnboardingContent
import com.dpsmpx.dpclock.ui.AlarmCheckDialog
import com.dpsmpx.dpclock.ui.SettingsScreen
import com.dpsmpx.dpclock.ui.SyncConflictsDialog
import com.dpsmpx.dpclock.ui.TaskEditor
import com.dpsmpx.dpclock.ui.TasksScreen
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
import com.dpsmpx.dpclock.model.SunMoment
import com.dpsmpx.dpclock.model.SunSignal
import com.dpsmpx.dpclock.model.SunSignalKind
import com.dpsmpx.dpclock.model.SunSignals
import com.dpsmpx.dpclock.model.Exceptions
import com.dpsmpx.dpclock.model.SunEvent
import com.dpsmpx.dpclock.model.SunTime
import com.dpsmpx.dpclock.ui.AlarmEditor
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

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
        DpClockApp.resetGraph()
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
        // Задачи (D-031) — только в своём снимке.
        DpClockApp.graph(app).tasks.preview(TaskPrefs())
    }

    /** Задачи (D-032): в «Часах», «Все задачи», редактор и раздел настроек. */
    @Test
    fun tasks() {
        val today = LocalDate.now()
        val repository = DpClockApp.graph(app).repository
        val weekly = Task("t3", "Созвон с командой", date = today, time = LocalTime.of(11, 0), reminder = 15, repeat = TaskRepeat.Weekly(setOf(today.dayOfWeek)), createdAt = 3L)
        val own = listOf(
            Task("t1", "Купить хлеб", date = today, createdAt = 1L),
            Task("t2", "Отчёт за октябрь", date = today.minusDays(2), reminder = 60, createdAt = 2L),
            weekly,
            Task("t4", "Подарок маме", note = "Цветы и открытка", date = today.plusDays(5), createdAt = 4L),
            Task("t5", "Прочитать книгу", createdAt = 5L),
        )
        runBlocking { own.forEach { repository.saveTask(it) } }
        val controller = Robolectric.buildActivity(MainActivity::class.java, MainActivity.intent(app, Section.CLOCK)).setup()
        save(controller.get(), "section-clock-tasks")
        controller.pause().stop().destroy()
        shoot("tasks-all") { DpClockTheme { TasksScreen(SnackbarHostState()) } }
        shoot("tasks-editor") { DpClockTheme { TaskEditor(weekly, today, onDismiss = {}) } }
        shoot("settings-tasks") { DpClockTheme { SettingsScreen(onBack = {}, initialSection = R.string.settings_tasks) } }

        // Постоянные (D-033): дерево, «прошло», «дольше обычного», доска.
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val flowers = Task("p1", "Полить цветы", permanent = true, parentId = "p0", urgency = TaskLevel.HIGH, difficulty = TaskLevel.LOW, remindLate = true, createdAt = 10L)
        val permanent = listOf(
            Task("p0", "Квартира", permanent = true, createdAt = 9L),
            flowers,
            Task("p2", "Поменять фильтр воды", permanent = true, parentId = "p0", urgency = TaskLevel.MEDIUM, difficulty = TaskLevel.HIGH, remindAfterDays = 90, createdAt = 11L),
            Task("p3", "Резервная копия ноутбука", permanent = true, urgency = TaskLevel.HIGH, difficulty = TaskLevel.HIGH, createdAt = 12L),
            Task("p4", "Проверить огнетушитель", permanent = true, urgency = TaskLevel.LOW, createdAt = 13L),
        )
        runBlocking {
            permanent.forEach { repository.saveTask(it) }
            for (ago in listOf(17 * day, 12 * day, 7 * day, 2 * day + 3 * 3_600_000L)) repository.addMark("p1", now - ago)
            repository.addMark("p2", now - 95 * day)
            for (ago in listOf(30 * day, 19 * day)) repository.addMark("p3", now - ago)
        }
        shoot("tasks-permanent") { DpClockTheme { TasksScreen(SnackbarHostState(), initialTab = 1) } }
        // Доска — за настройкой (D-036).
        DpClockApp.graph(app).tasks.preview(TaskPrefs(advanced = true))
        shoot("tasks-board") { DpClockTheme { TasksScreen(SnackbarHostState(), initialTab = 2) } }
        shoot("tasks-editor-permanent") { DpClockTheme { TaskEditor(flowers, today, onDismiss = {}) } }
        runBlocking { (own + permanent).forEach { repository.deleteTask(it.id) } }
        DpClockApp.graph(app).tasks.preview(TaskPrefs())
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
        shoot("settings-sync") { DpClockTheme { SettingsScreen(onBack = {}, initialSection = R.string.settings_sync) } }
    }

    /** Редактор будильника со временем по солнцу (D-038). */
    @Test
    fun alarmBySun() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        DpClockApp.graph(app).sun.setLocation("Москва", 55.7558, 37.6173, System.currentTimeMillis())
        val alarm = Alarm("s", 18, 0, Repeat.Weekly(DayOfWeek.entries.take(5).toSet()), enabled = true, createdAt = 1L, label = "Закат", sun = SunTime(SunEvent.SUNSET, -10), key = true)
        shoot("alarm-editor-sun") {
            DpClockTheme { AlarmEditor(alarm, Settings(), emptyList(), Exceptions.NONE, onDismiss = {}, onSave = {}, onDelete = {}) }
        }
    }

    /** Самопроверка «прозвонит ли будильник» (D-036, п. 4). */
    @Test
    fun alarmCheck() {
        seed()
        // Строка «Будильник в Android» на снимке — «проблема»: `getNextAlarmClock()` у Robolectric
        // отдаёт не самый ранний будильник. На телефоне Android отдаёт ближайший.
        shoot("alarm-check") { DpClockTheme { AlarmCheckDialog(onDismiss = {}) } }
    }

    /** Восход и закат (D-035): место по геолокации, времена на сегодня, сигналы и мелодия будильника. */
    @Test
    fun sun() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        DpClockApp.graph(app).sun.setLocation("Москва", 55.7558, 37.6173, System.currentTimeMillis())
        runBlocking {
            DpClockApp.graph(app).repository.updateSettings {
                it.copy(
                    sun = SunSignals(
                        sunrise = SunSignal(enabled = true, moments = listOf(SunMoment(5), SunMoment(0, SunSignalKind.ALARM))),
                        sunset = SunSignal(enabled = true),
                    ),
                )
            }
        }
        shoot("settings-sun") { DpClockTheme { SettingsScreen(onBack = {}, initialSection = R.string.settings_sun) } }
    }

    /** Синхронизация (D-030): окно расхождений с будильником и настройками. */
    @Test
    fun syncConflicts() {
        val alarm = Alarm("1", 7, 0, Repeat.Weekly(DayOfWeek.entries.take(5).toSet()), enabled = true, createdAt = 1L, label = "Подъём")
        val mine = SyncItems.snapshot(Backup(alarms = listOf(alarm), settings = Settings()))
        val theirs = SyncItems.snapshot(Backup(alarms = listOf(alarm.copy(hour = 6, minute = 30)), settings = Settings(snoozeMinutes = 5)))
        val at = 1_793_430_000_000L
        val conflicts = listOf("alarm:1", SyncItems.SETTINGS).map { key ->
            SyncConflict(key, mine.getValue(key), at, theirs.getValue(key), at + 600_000L, "Redmi Pad")
        }
        shoot("sync-conflicts") { DpClockTheme { SyncConflictsDialog(conflicts) {} } }
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
        // Как при запуске MainActivity: запись в базу будит потоки Room в Robolectric.
        persistLater { DpClockApp.graph(app).repository.rescheduleAll() }
        save(controller.get(), name)
        controller.pause().stop().destroy()
    }

    /** Будильники, пресеты и идущий таймер — как у человека, который приложением пользуется. */
    private fun seed() = runBlocking {
        val repository = DpClockApp.graph(app).repository
        val workdays = DayOfWeek.entries.take(5).toSet()
        repository.addAlarm(7, 0)
        val first = repository.surfaceData().alarms.first()
        // Ключевой (D-037): выделен в списке.
        repository.saveAlarm(first.copy(label = "Подъём", repeat = Repeat.Weekly(workdays), key = true))
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
