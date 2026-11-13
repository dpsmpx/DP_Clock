package com.dpsmpx.dpclock.platform

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri
import com.dpsmpx.dpclock.BuildConfig
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.data.ClockRepository
import com.dpsmpx.dpclock.model.AutoBackupPeriod
import com.dpsmpx.dpclock.model.AutoBackupRules
import com.dpsmpx.dpclock.model.BackupFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/** Копия по расписанию: как часто, куда, когда была последняя и удалась ли. */
data class AutoBackupState(
    val period: AutoBackupPeriod = AutoBackupPeriod.OFF,
    val folder: Uri? = null,
    val folderName: String = "",
    val lastAt: Long = 0L,
    val failed: Boolean = false,
)

/**
 * Резервная копия по расписанию (D-036, п. 4): файл переноса (D-018) раз в день или
 * неделю в папку, которую выбрал человек в системном окне, — в памяти телефона или
 * в облаке. В папке остаются последние [AutoBackupRules.KEEP] копий. Папка и
 * расписание — свои у телефона: в базу, перенос и синхронизацию не входят.
 */
class AutoBackup(private val context: Context, private val repository: ClockRepository) {
    private val prefs = context.getSharedPreferences("autobackup", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<AutoBackupState> = mutableState

    suspend fun setPeriod(period: AutoBackupPeriod) {
        save(mutableState.value.copy(period = period))
        AutoBackupJobs.schedule(context, period != AutoBackupPeriod.OFF)
        run()
    }

    /** Папка из системного окна: доступ берётся надолго; первая копия — сразу. false — доступ не дали. */
    suspend fun setFolder(uri: Uri): Boolean {
        val taken = runCatching { context.contentResolver.takePersistableUriPermission(uri, FLAGS) }.isSuccess
        if (!taken) return false
        mutableState.value.folder?.takeIf { it != uri }?.let { old -> runCatching { context.contentResolver.releasePersistableUriPermission(old, FLAGS) } }
        val name = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/') }.getOrDefault("")
        save(mutableState.value.copy(folder = uri, folderName = name, lastAt = 0L, failed = false))
        return run()
    }

    /** Приложение открыли: копия, если срок прошёл, а задание Android не пришло. */
    fun opened() {
        if (mutableState.value.period == AutoBackupPeriod.OFF) return
        AutoBackupJobs.schedule(context, true)
        DpClockApp.scope.launch { run() }
    }

    /**
     * Сохранить копию, если пора (или [force]). true — копия записана или ещё не пора;
     * false — папка не выбрана или записать не вышло: в настройках видно «не удалось».
     */
    suspend fun run(force: Boolean = false): Boolean = mutex.withLock {
        val current = mutableState.value
        val folder = current.folder ?: return@withLock false
        val now = System.currentTimeMillis()
        if (!force && !AutoBackupRules.isDue(current.period, current.lastAt, now)) return@withLock true
        val text = BackupFormat.encode(repository.exportBackup(), Instant.now(), BuildConfig.VERSION_NAME)
        val written = withContext(Dispatchers.IO) { runCatching { write(folder, AutoBackupRules.fileName(LocalDate.now()), text) }.isSuccess }
        save(current.copy(lastAt = if (written) now else current.lastAt, failed = !written))
        written
    }

    /** Файл за сегодня заменяется; лишние старые копии удаляются. Чужие файлы не трогаются. */
    private fun write(folder: Uri, name: String, text: String) {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val existing = children(folder)
        for ((id, childName) in existing) {
            if (childName == name) DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(folder, id))
        }
        val file = DocumentsContract.createDocument(resolver, parent, BackupFormat.MIME, name) ?: throw IOException("файл не создан")
        (resolver.openOutputStream(file, "wt") ?: throw IOException("нет потока")).use { it.write(text.toByteArray()) }
        val stale = AutoBackupRules.stale(existing.map { it.second }.filter { it != name } + name).toSet()
        for ((id, childName) in existing) {
            if (childName in stale) runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(folder, id)) }
        }
    }

    /** Файлы папки: номер документа и имя. */
    private fun children(folder: Uri): List<Pair<String, String>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1).orEmpty())
            }
        }.orEmpty()
    }

    private fun read() = AutoBackupState(
        period = AutoBackupPeriod.entries.firstOrNull { it.name == prefs.getString(PERIOD, null) } ?: AutoBackupPeriod.OFF,
        folder = prefs.getString(FOLDER, null)?.toUri(),
        folderName = prefs.getString(FOLDER_NAME, null).orEmpty(),
        lastAt = prefs.getLong(LAST_AT, 0L),
        failed = prefs.getBoolean(FAILED, false),
    )

    private fun save(state: AutoBackupState) {
        prefs.edit {
            putString(PERIOD, state.period.name)
            putString(FOLDER, state.folder?.toString())
            putString(FOLDER_NAME, state.folderName)
            putLong(LAST_AT, state.lastAt)
            putBoolean(FAILED, state.failed)
        }
        mutableState.value = state
    }

    private companion object {
        const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        const val PERIOD = "period"
        const val FOLDER = "folder"
        const val FOLDER_NAME = "folderName"
        const val LAST_AT = "lastAt"
        const val FAILED = "failed"
    }
}

/** Задание Android: несколько раз в сутки проверить, не пора ли сохранить копию. Без расписания задания нет. */
object AutoBackupJobs {
    private const val PERIODIC = 7_004
    private const val PERIOD_MILLIS = 6 * 3_600_000L

    fun schedule(context: Context, enabled: Boolean) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled) {
            jobs.cancel(PERIODIC)
            return
        }
        if (jobs.getPendingJob(PERIODIC) != null) return
        runCatching {
            jobs.schedule(
                JobInfo.Builder(PERIODIC, ComponentName(context, AutoBackupJob::class.java))
                    .setPeriodic(PERIOD_MILLIS)
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}

class AutoBackupJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val context = applicationContext
        DpClockApp.scope.launch {
            try {
                DpClockApp.graph(context).autoBackup.run()
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
