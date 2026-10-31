package com.dpsmpx.dpclock.platform

import android.content.Context
import android.os.UserManager
import com.dpsmpx.dpclock.data.ClockRepository
import com.dpsmpx.dpclock.model.DayTask
import com.dpsmpx.dpclock.model.Task
import com.dpsmpx.dpclock.model.TaskPrefs
import com.dpsmpx.dpclock.model.TaskRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.time.LocalDate
import java.time.LocalDateTime

/** Задачи дня — свои, сегодня и просроченные (D-032; с D-034 — только свои). */
fun dayTasks(own: List<Task>, today: LocalDate): List<DayTask> = TaskRules.forDay(own.mapNotNull(TaskRules::day), today)

/**
 * Где показывать задачи дня и уведомление после утреннего будильника (D-031, п. 3).
 * Сами задачи — в базе ([ClockRepository]). Выбор показа — файлом в хранилище
 * учётных данных, вне резервной копии, переноса и синхронизации: он свой у телефона.
 *
 * Google Tasks убран (D-034): ключи прежних версий — Todoist, TickTick (0.18.0) и
 * Google (0.19.0–0.20.0) — стираются с телефона при первом обращении после разблокировки.
 */
class TaskStore(private val context: Context, private val notifier: Notifier, private val repository: ClockRepository) {
    private val dir = context.noBackupFilesDir
    private val prefsFile = File(dir, "tasks.json")
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(readPrefs() ?: TaskPrefs())

    @Volatile
    private var loaded = unlocked()

    @Volatile
    private var cleaned = false

    val state: StateFlow<TaskPrefs> = mutableState

    /** Выбор сейчас; создан до разблокировки — после неё перечитывает файл (виджет после перезагрузки). */
    fun current(): TaskPrefs {
        if (!loaded && unlocked()) {
            readPrefs()?.let { mutableState.value = it }
            loaded = true
        }
        return mutableState.value
    }

    /** Где показывать: «Часы», после будильника, виджет. */
    suspend fun updateShow(change: (TaskPrefs) -> TaskPrefs) = mutex.withLock { save(change(current())) }

    /**
     * Утренний будильник выключен (выбор автора — «После будильника»): уведомление
     * «Задачи на сегодня», один раз в день.
     */
    suspend fun afterAlarm() {
        val prefs = current()
        if (!prefs.showAfterAlarm) return
        val now = LocalDateTime.now()
        if (!TaskRules.isMorning(now.toLocalTime()) || prefs.notifiedOn == now.toLocalDate()) return
        val tasks = dayTasks(repository.currentTasks(), now.toLocalDate())
        mutex.withLock { save(current().copy(notifiedOn = now.toLocalDate())) }
        if (tasks.isNotEmpty()) notifier.tasks(tasks)
    }

    /** Для снимков экранов. */
    internal fun preview(prefs: TaskPrefs) {
        loaded = true
        mutableState.value = prefs
    }

    private fun unlocked(): Boolean = context.getSystemService(UserManager::class.java)?.isUserUnlocked != false

    private fun readPrefs(): TaskPrefs? = if (!unlocked()) null else runCatching { TaskPrefs.decode(prefsFile.readText()) }.getOrNull()

    private suspend fun save(prefs: TaskPrefs) {
        mutableState.value = prefs
        withContext(Dispatchers.IO) {
            if (!cleaned) forgetKeys()
            runCatching {
                dir.mkdirs()
                val temp = File(dir, prefsFile.name + ".tmp")
                temp.writeText(TaskPrefs.encode(prefs))
                temp.renameTo(prefsFile)
            }
        }
        // Виджет «Часы» показывает задачи (выбор автора).
        Surfaces.changed(context)
    }

    /** Ключи таск-менеджеров прежних версий и их ключ Android Keystore — с телефона долой. */
    private fun forgetKeys() {
        if (!unlocked()) return
        File(dir, OLD_KEYS).delete()
        try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(OLD_ALIAS)
        } catch (e: GeneralSecurityException) {
            // Ключа нет — и не надо.
        } catch (e: java.io.IOException) {
            // Хранилище ключей недоступно — файла ключей уже нет, ключ без него бесполезен.
        }
        cleaned = true
    }

    /** После запуска: стереть старые ключи и переписать файл без задач Google. */
    suspend fun cleanUp() = mutex.withLock { if (!cleaned && unlocked()) save(current()) }

    private companion object {
        const val OLD_KEYS = "tasks-keys.bin"
        const val KEYSTORE = "AndroidKeyStore"
        const val OLD_ALIAS = "dpclock-tasks"
    }
}
