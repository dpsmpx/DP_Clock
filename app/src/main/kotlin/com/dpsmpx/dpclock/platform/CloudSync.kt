package com.dpsmpx.dpclock.platform

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.dpsmpx.dpclock.BuildConfig
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.data.ClockRepository
import com.dpsmpx.dpclock.data.deviceStorage
import com.dpsmpx.dpclock.model.Json
import com.dpsmpx.dpclock.model.SyncChoice
import com.dpsmpx.dpclock.model.SyncConflict
import com.dpsmpx.dpclock.model.SyncFormat
import com.dpsmpx.dpclock.model.SyncItems
import com.dpsmpx.dpclock.model.SyncRules
import com.dpsmpx.dpclock.model.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Почему последний проход не удался (D-030). */
enum class SyncError {
    /** Файл не открылся: удалён, нет сети и копии на телефоне, отозвано разрешение. */
    UNAVAILABLE,

    /** В файле не синхронизация DP Clock или он повреждён — такой файл не перезаписывается. */
    FOREIGN,

    /** Прочитать удалось, записать — нет. */
    WRITE,
}

/** Что видно в настройках «Синхронизация». [file] — имя общего файла; null — синхронизация выключена. */
data class SyncStatus(
    val file: String? = null,
    val lastAt: Long = 0L,
    val error: SyncError? = null,
    val running: Boolean = false,
    val conflicts: List<SyncConflict> = emptyList(),
)

/** Что в выбранном файле — до подключения. */
sealed interface SyncProbe {
    data class Failed(val error: SyncError) : SyncProbe

    /** [alarms] — будильников в файле, [items] — всех записей; ноль — файл новый. */
    data class Ready(val uri: Uri, val name: String, val alarms: Int, val items: Int) : SyncProbe
}

/**
 * Синхронизация общим файлом в облаке (D-030). Файл выбирает человек в системном
 * окне — Google Диск или другое облако; приложение получает постоянное разрешение
 * читать и писать только его. Каждый проход: прочитать файл, слить с базой
 * ([SyncRules]) и, если что-то поменялось, записать обратно. Когда:
 * - через минуту после правки — если правка есть, чего файл не видел;
 * - при открытии приложения;
 * - раз в час заданием Android, при любой сети.
 *
 * Файл и состояние — в хранилище устройства без резервной копии: копия
 * приложения на новом телефоне начинает синхронизацию заново, с выбора файла.
 */
class CloudSync(private val context: Context, private val repository: ClockRepository, private val notifier: Notifier) {
    private val dir = context.deviceStorage().noBackupFilesDir
    private val configFile = File(dir, "sync-config.json")
    private val stateFile = File(dir, "sync-state.json")
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(readConfig()?.let { SyncStatus(it.name, it.lastAt, it.error) } ?: SyncStatus())
    private var pushJob: Job? = null

    @Volatile
    private var lastAttempt = 0L

    val status: StateFlow<SyncStatus> = mutableStatus

    /** Данные изменились: через минуту записать, если среди изменений есть то, что синхронизируется. */
    fun changed() {
        if (mutableStatus.value.file == null) return
        synchronized(this) {
            pushJob?.cancel()
            pushJob = DpClockApp.scope.launch {
                delay(PUSH_DELAY_MILLIS)
                val local = SyncItems.snapshot(repository.exportBackup())
                if (SyncRules.hasLocalChanges(local, readState())) run()
            }
        }
    }

    /** Приложение открыли: прочитать файл, если не читали только что, и следить раз в час. */
    fun opened() {
        val enabled = mutableStatus.value.file != null
        SyncJobs.schedule(context, enabled)
        if (enabled && System.currentTimeMillis() - lastAttempt > OPEN_PAUSE_MILLIS) DpClockApp.scope.launch { run() }
    }

    /**
     * Один проход. [choices] — ответы на расхождения; [replace] и [fresh] — первая
     * синхронизация с файлом («Взять данные из файла» или «Объединить»). Возвращает,
     * удался ли он.
     */
    suspend fun run(choices: Map<String, SyncChoice> = emptyMap(), replace: Boolean = false, fresh: Boolean = false): Boolean {
        mutex.withLock { return pass(choices, replace, fresh) }
    }

    private suspend fun pass(choices: Map<String, SyncChoice>, replace: Boolean, fresh: Boolean): Boolean {
        val config = readConfig() ?: return false
        // До первой разблокировки облако недоступно: его приложения ещё не работают.
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return false
        lastAttempt = System.currentTimeMillis()
        mutableStatus.update { it.copy(running = true) }
        try {
            val text = io { read(config.uri) } ?: return fail(config, SyncError.UNAVAILABLE)
            val remote = SyncFormat.decode(text) ?: return fail(config, SyncError.FOREIGN)
            val state = if (fresh) SyncState() else readState()
            val now = System.currentTimeMillis()
            val name = deviceName()
            val result = repository.sync(replace) { local, hold -> SyncRules.run(local, state, remote, name, now, choices, hold) }
            // Состояние — сразу: изменения уже в базе. Не записался файл — следующий проход запишет снова.
            writeState(result.state)
            if (result.upload && io { write(config.uri, SyncFormat.encode(result.file, BuildConfig.VERSION_NAME)) } == null) {
                return fail(config, SyncError.WRITE)
            }
            writeConfig(config.copy(lastAt = now, error = null))
            val before = mutableStatus.value.conflicts.map { it.key }.toSet()
            when {
                result.conflicts.isEmpty() -> notifier.cancelSyncConflicts()
                result.conflicts.map { it.key }.toSet() != before -> notifier.syncConflicts(result.conflicts.size)
            }
            mutableStatus.update { it.copy(lastAt = now, error = null, conflicts = result.conflicts) }
            return true
        } finally {
            mutableStatus.update { it.copy(running = false) }
        }
    }

    /**
     * Выбранный файл: взять постоянное разрешение и прочитать. Чужой или недоступный
     * файл — разрешение отдаётся обратно.
     */
    suspend fun probe(uri: Uri): SyncProbe {
        if (io { context.contentResolver.takePersistableUriPermission(uri, FLAGS) } == null) return SyncProbe.Failed(SyncError.UNAVAILABLE)
        val text = io { read(uri) }
        val file = text?.let { SyncFormat.decode(it) }
        if (file == null) {
            release(uri)
            return SyncProbe.Failed(if (text == null) SyncError.UNAVAILABLE else SyncError.FOREIGN)
        }
        val live = file.items.values.filterNot { it.deleted }
        return SyncProbe.Ready(uri, io { displayName(uri) } ?: SyncFormat.FILE_NAME, live.count { it.key.startsWith(SyncItems.ALARM) }, live.size)
    }

    /** Выбор файла отменили: разрешение — обратно, если синхронизация идёт не с ним. */
    fun cancel(file: SyncProbe.Ready) {
        if (readConfig()?.uri != file.uri) release(file.uri)
    }

    /** Синхронизироваться с файлом с нуля: [replace] — свои данные заменить данными из файла. */
    suspend fun connect(file: SyncProbe.Ready, replace: Boolean): Boolean {
        val old = readConfig()
        if (old != null && old.uri != file.uri) release(old.uri)
        writeConfig(Config(file.uri, file.name, 0L, null))
        withContext(Dispatchers.IO) { stateFile.delete() }
        mutableStatus.value = SyncStatus(file.name)
        SyncJobs.schedule(context, true)
        return run(replace = replace, fresh = true)
    }

    /** Отключить: данные на телефоне остаются, файл в облаке — тоже. */
    suspend fun disconnect() = mutex.withLock {
        synchronized(this) { pushJob?.cancel() }
        readConfig()?.let { release(it.uri) }
        withContext(Dispatchers.IO) {
            configFile.delete()
            stateFile.delete()
        }
        mutableStatus.value = SyncStatus()
        notifier.cancelSyncConflicts()
        SyncJobs.schedule(context, false)
    }

    private fun fail(config: Config, error: SyncError): Boolean {
        writeConfig(config.copy(error = error))
        mutableStatus.update { it.copy(error = error) }
        return false
    }

    private fun read(uri: Uri): String? = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }

    /** Записать файл целиком. Не всякое облако понимает «wt» (с обрезкой) — тогда «w». */
    private fun write(uri: Uri, text: String) {
        val resolver = context.contentResolver
        val stream = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: resolver.openOutputStream(uri, "w") ?: throw IOException("no stream")
        stream.use { it.write(text.encodeToByteArray()) }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun release(uri: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, FLAGS) }
    }

    /** Имя телефона из «О телефоне»; нет — модель. Видно другому телефону в расхождении. */
    private fun deviceName(): String =
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Работа с файлом облака — в потоке ввода-вывода; ошибка — null. */
    private suspend fun <T> io(block: () -> T): T? = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IllegalStateException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        }
    }

    private data class Config(val uri: Uri, val name: String, val lastAt: Long, val error: SyncError?)

    private fun readConfig(): Config? = runCatching {
        val root = Json.parse(configFile.readText()) as Map<*, *>
        Config(
            uri = (root["uri"] as String).toUri(),
            name = root["name"] as String,
            lastAt = root["lastAt"] as Long,
            error = (root["error"] as String?)?.let { name -> SyncError.entries.firstOrNull { it.name == name } },
        )
    }.getOrNull()

    private fun writeConfig(config: Config) {
        dir.mkdirs()
        configFile.writeText(Json.write(mapOf("uri" to config.uri.toString(), "name" to config.name, "lastAt" to config.lastAt, "error" to config.error?.name)))
    }

    private fun readState(): SyncState = runCatching { SyncFormat.decodeState(stateFile.readText()) }.getOrDefault(SyncState())

    private fun writeState(state: SyncState) {
        dir.mkdirs()
        val temp = File(dir, stateFile.name + ".tmp")
        temp.writeText(SyncFormat.encodeState(state))
        temp.renameTo(stateFile)
    }

    private companion object {
        const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        const val PUSH_DELAY_MILLIS = 60_000L
        const val OPEN_PAUSE_MILLIS = 60_000L
    }
}

/** Задание Android: раз в час прочитать общий файл (D-030). Без синхронизации задания нет. */
object SyncJobs {
    private const val PERIODIC = 7_003
    private const val PERIOD_MILLIS = 3_600_000L

    fun schedule(context: Context, enabled: Boolean) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled) {
            jobs.cancel(PERIODIC)
            return
        }
        if (jobs.getPendingJob(PERIODIC) != null) return
        runCatching {
            jobs.schedule(
                JobInfo.Builder(PERIODIC, ComponentName(context, SyncJob::class.java))
                    .setPeriodic(PERIOD_MILLIS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}

class SyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val context = applicationContext
        DpClockApp.scope.launch {
            try {
                DpClockApp.graph(context).cloud.run()
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
