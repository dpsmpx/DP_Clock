package com.dpsmpx.dpclock.platform

import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.dpsmpx.dpclock.BuildConfig
import com.dpsmpx.dpclock.model.DayTask
import com.dpsmpx.dpclock.model.GoogleTasksApi
import com.dpsmpx.dpclock.model.Json
import com.dpsmpx.dpclock.model.TaskCache
import com.dpsmpx.dpclock.model.TaskFormatException
import com.dpsmpx.dpclock.model.TaskRules
import com.dpsmpx.dpclock.model.TaskService
import com.dpsmpx.dpclock.model.TickTickApi
import com.dpsmpx.dpclock.model.TodoistApi
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Что видят экраны (D-031). [refreshing] — идёт обновление. */
data class TaskState(val cache: TaskCache = TaskCache(), val refreshing: Boolean = false) {
    /** Задачи подключённых сервисов на [today]: сегодня и просроченные. */
    fun today(today: LocalDate): List<DayTask> = TaskRules.forDay(cache.tasks.filter { it.service in cache.connected }, today)
}

/** Итог подключения сервиса. */
enum class TaskConnect {
    OK,

    /** Ключ не подошёл или вход отозван. */
    WRONG_KEY,

    /** Нет связи. */
    NO_NETWORK,

    /** Google не знает приложение: нет проекта Google Cloud с ключом DP Clock (D-031, п. 4). */
    NOT_REGISTERED,
    FAILED,
}

/**
 * Задачи из таск-менеджеров (D-031). Ключи Todoist и TickTick — зашифрованы ключом
 * Android Keystore, вход Google хранит Google Play services. Всё — только на этом
 * телефоне, вне резервной копии, файла переноса и синхронизации. Задачи — кэшем в
 * файле: экраны и виджет показывают их без сети. В сеть — только к API выбранных
 * сервисов, когда открыты «Часы», после утреннего будильника и из настроек.
 */
class TaskStore(private val context: Context, private val notifier: Notifier) {
    /** Хранилище учётных данных: файлы открываются только после разблокировки. */
    private val dir = context.noBackupFilesDir
    private val cacheFile = File(dir, "tasks.json")
    private val vault = TaskVault(File(dir, "tasks-keys.bin"))
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(TaskState(readCache() ?: TaskCache()))
    private var loaded = mutableState.value.cache != TaskCache()

    val state: StateFlow<TaskState> = mutableState

    /** Состояние сейчас; создан до разблокировки — после неё перечитывает кэш (виджет после перезагрузки). */
    fun current(): TaskState {
        if (!loaded && unlocked()) {
            readCache()?.let { cache -> mutableState.update { it.copy(cache = cache) } }
            loaded = true
        }
        return mutableState.value
    }

    /** Обновить задачи всех подключённых сервисов; [force] — даже если обновляли только что. */
    suspend fun refresh(force: Boolean = false): Boolean = mutex.withLock { refreshLocked(force) }

    private suspend fun refreshLocked(force: Boolean, only: TaskService? = null): Boolean {
        if (!unlocked()) return false
        if (!loaded) readCache()?.let { cache -> mutableState.update { it.copy(cache = cache) } }
        loaded = true
        val cache = mutableState.value.cache
        val services = cache.connected.filter { only == null || it == only }
        if (services.isEmpty()) return false
        val now = System.currentTimeMillis()
        if (!force && only == null && now - cache.fetchedAt < TaskRules.REFRESH_MILLIS) return false
        mutableState.update { it.copy(refreshing = true) }
        try {
            val keys = withContext(Dispatchers.IO) { vault.read() }
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            var tasks = cache.tasks
            val failures = cache.failures.toMutableMap()
            for (service in services) {
                when (val result = fetch(service, keys, today, zone)) {
                    is Fetched.Done -> {
                        tasks = tasks.filter { it.service != service } + result.tasks
                        failures.remove(service)
                    }
                    is Fetched.Failed -> failures[service] = result.code
                }
            }
            save(mutableState.value.cache.copy(tasks = tasks, failures = failures, fetchedAt = now))
            return true
        } finally {
            mutableState.update { it.copy(refreshing = false) }
        }
    }

    /** Отметить «сделано» (выбор автора): сразу убрать с экрана, ошибка — вернуть. */
    suspend fun complete(task: DayTask): Boolean {
        val same = { other: DayTask -> other.service == task.service && other.id == task.id }
        mutableState.update { it.copy(cache = it.cache.copy(tasks = it.cache.tasks.filterNot(same))) }
        return mutex.withLock { completeLocked(task, same) }
    }

    private suspend fun completeLocked(task: DayTask, same: (DayTask) -> Boolean): Boolean {
        val before = mutableState.value.cache
        save(before.copy(tasks = before.tasks.filterNot(same)))
        val keys = withContext(Dispatchers.IO) { vault.read() }
        val code = when (task.service) {
            TaskService.TODOIST -> keys[TaskService.TODOIST.name]?.let { http("POST", TodoistApi.closeUrl(task.id), it).code } ?: 0
            TaskService.TICKTICK -> keys[TaskService.TICKTICK.name]?.let { http("POST", TickTickApi.completeUrl(task.listId, task.id), it, body = "").code } ?: 0
            TaskService.GOOGLE -> google { token ->
                http("POST", GoogleTasksApi.taskUrl(task.listId, task.id), token, GoogleTasksApi.COMPLETE_BODY, override = "PATCH")
            }.code
        }
        val done = code in 200..299
        if (!done) {
            val now = mutableState.value.cache
            save(now.copy(tasks = now.tasks + task))
        }
        return done
    }

    /** Подключить Todoist или TickTick личным ключом: ключ проверяется запросом задач. */
    suspend fun connect(service: TaskService, key: String): TaskConnect = mutex.withLock {
        if (!unlocked()) return@withLock TaskConnect.FAILED
        val zone = ZoneId.systemDefault()
        when (val result = fetch(service, mapOf(service.name to key), LocalDate.now(zone), zone)) {
            is Fetched.Failed -> failure(result.code)
            is Fetched.Done -> {
                withContext(Dispatchers.IO) { vault.write(vault.read() + (service.name to key)) }
                val cache = mutableState.value.cache
                save(
                    cache.copy(
                        connected = cache.connected + service,
                        tasks = cache.tasks.filter { it.service != service } + result.tasks,
                        failures = cache.failures - service,
                    ),
                )
                TaskConnect.OK
            }
        }
    }

    /** Вход Google прошёл — Google Tasks подключён; задачи — сразу. */
    suspend fun connectGoogle(): TaskConnect = mutex.withLock {
        val cache = mutableState.value.cache
        save(cache.copy(connected = cache.connected + TaskService.GOOGLE))
        refreshLocked(force = true, only = TaskService.GOOGLE)
        when (val code = mutableState.value.cache.failures[TaskService.GOOGLE]) {
            null -> TaskConnect.OK
            else -> failure(code)
        }
    }

    /** Отключить: ключ удаляется, задачи сервиса — тоже. */
    suspend fun disconnect(service: TaskService) = mutex.withLock {
        withContext(Dispatchers.IO) { vault.write(vault.read() - service.name) }
        if (service == TaskService.GOOGLE) GoogleTasksAuth.token(context)?.let { GoogleTasksAuth.clear(context, it) }
        val cache = mutableState.value.cache
        save(cache.copy(connected = cache.connected - service, tasks = cache.tasks.filter { it.service != service }, failures = cache.failures - service))
    }

    /** Где показывать: «Часы», после будильника, виджет. */
    suspend fun updateShow(change: (TaskCache) -> TaskCache) = mutex.withLock { save(change(mutableState.value.cache)) }

    /**
     * Утренний будильник выключен (выбор автора — «После будильника»): свежие задачи
     * и уведомление «Задачи на сегодня», один раз в день.
     */
    suspend fun afterAlarm() {
        val cache = mutableState.value.cache
        if (cache.connected.isEmpty() || !cache.showAfterAlarm) return
        val now = LocalDateTime.now()
        if (!TaskRules.isMorning(now.toLocalTime()) || cache.notifiedOn == now.toLocalDate()) return
        refresh(force = true)
        val tasks = mutableState.value.today(now.toLocalDate())
        mutex.withLock { save(mutableState.value.cache.copy(notifiedOn = now.toLocalDate())) }
        if (tasks.isNotEmpty()) notifier.tasks(tasks)
    }

    /** Для снимков экранов: задачи без сети и ключей. */
    internal fun preview(cache: TaskCache) {
        loaded = true
        mutableState.value = TaskState(cache.copy(fetchedAt = System.currentTimeMillis()))
    }

    private fun failure(code: Int): TaskConnect = when (code) {
        401, 403, TaskCache.SIGN_IN -> TaskConnect.WRONG_KEY
        0 -> TaskConnect.NO_NETWORK
        NOT_REGISTERED -> TaskConnect.NOT_REGISTERED
        else -> TaskConnect.FAILED
    }

    private sealed interface Fetched {
        data class Done(val tasks: List<DayTask>) : Fetched

        data class Failed(val code: Int) : Fetched
    }

    private suspend fun fetch(service: TaskService, keys: Map<String, String>, today: LocalDate, zone: ZoneId): Fetched {
        try {
            return fetchOrThrow(service, keys, today, zone)
        } catch (e: TaskFormatException) {
            return Fetched.Failed(FORMAT)
        }
    }

    private suspend fun fetchOrThrow(service: TaskService, keys: Map<String, String>, today: LocalDate, zone: ZoneId): Fetched {
        return when (service) {
            TaskService.TODOIST -> {
                val key = keys[service.name] ?: return Fetched.Failed(TaskCache.SIGN_IN)
                val tasks = mutableListOf<DayTask>()
                var cursor: String? = null
                for (page in 0 until MAX_PAGES) {
                    val response = http("GET", TodoistApi.filterUrl(cursor), key)
                    if (response.code != 200) return Fetched.Failed(response.code)
                    val parsed = TodoistApi.parse(response.text, zone)
                    tasks += parsed.tasks
                    cursor = parsed.next ?: break
                }
                Fetched.Done(tasks)
            }
            TaskService.TICKTICK -> {
                val key = keys[service.name] ?: return Fetched.Failed(TaskCache.SIGN_IN)
                val response = http("POST", TickTickApi.FILTER_URL, key, TickTickApi.filterBody(today, zone))
                if (response.code != 200) Fetched.Failed(response.code) else Fetched.Done(TickTickApi.parse(response.text, zone))
            }
            TaskService.GOOGLE -> {
                val tasks = mutableListOf<DayTask>()
                val lists = google { token -> http("GET", GoogleTasksApi.listsUrl(null), token) }
                if (lists.code != 200) return Fetched.Failed(lists.code)
                for (listId in GoogleTasksApi.parseLists(lists.text).first.take(GoogleTasksApi.MAX_LISTS)) {
                    var pageToken: String? = null
                    for (page in 0 until MAX_PAGES) {
                        val response = google { token -> http("GET", GoogleTasksApi.tasksUrl(listId, today, pageToken), token) }
                        if (response.code != 200) return Fetched.Failed(response.code)
                        val parsed = GoogleTasksApi.parseTasks(response.text, listId)
                        tasks += parsed.tasks
                        pageToken = parsed.next ?: break
                    }
                }
                Fetched.Done(tasks)
            }
        }
    }

    /**
     * Запрос к Google с токеном Google Play services. Токен истёк (401) — сбросить
     * и попросить новый один раз. Нужен вход заново — [TaskCache.SIGN_IN].
     */
    private suspend fun google(call: suspend (String) -> Response): Response {
        val token = when (val auth = GoogleTasksAuth.silent(context)) {
            is GoogleAuth.Granted -> auth.token
            is GoogleAuth.NeedsUi -> return Response(TaskCache.SIGN_IN, "")
            is GoogleAuth.Failed -> return Response(if (auth.code == CommonStatusCodes.DEVELOPER_ERROR) NOT_REGISTERED else 0, "")
        }
        val first = call(token)
        if (first.code != 401) return first
        GoogleTasksAuth.clear(context, token)
        val again = (GoogleTasksAuth.silent(context) as? GoogleAuth.Granted)?.token ?: return Response(TaskCache.SIGN_IN, "")
        return call(again)
    }

    private class Response(val code: Int, val text: String)

    /** HTTPS к API сервиса; [override] — метод, которого нет у `HttpURLConnection` (PATCH у Google). Нет связи — код 0. */
    private suspend fun http(method: String, url: String, token: String, body: String? = null, override: String? = null): Response =
        withContext(Dispatchers.IO) {
            val connection = try {
                URL(url).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                return@withContext Response(0, "")
            }
            try {
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.requestMethod = method
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "DP_Clock/" + BuildConfig.VERSION_NAME)
                override?.let { connection.setRequestProperty("X-HTTP-Method-Override", it) }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.encodeToByteArray()) }
                }
                val code = connection.responseCode
                Response(code, if (code in 200..299) connection.inputStream.use { read(it) } else "")
            } catch (e: IOException) {
                Response(0, "")
            } finally {
                connection.disconnect()
            }
        }

    private fun read(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            out.write(chunk, 0, read)
            if (out.size() > MAX_BYTES) throw IOException("слишком большой ответ")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun unlocked(): Boolean = context.getSystemService(UserManager::class.java)?.isUserUnlocked != false

    private fun readCache(): TaskCache? = if (!unlocked()) null else runCatching { TaskCache.decode(cacheFile.readText()) }.getOrNull()

    private suspend fun save(cache: TaskCache) {
        mutableState.update { it.copy(cache = cache) }
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val temp = File(dir, cacheFile.name + ".tmp")
                temp.writeText(TaskCache.encode(cache))
                temp.renameTo(cacheFile)
            }
        }
        // Виджет «Часы» показывает задачи (выбор автора).
        Surfaces.changed(context)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_PAGES = 5

        /** Свои коды отказа рядом с кодами HTTP. */
        const val NOT_REGISTERED = -2
        const val FORMAT = -3
    }
}

/** Ответ Google Play services на просьбу о доступе к Google Tasks. */
sealed interface GoogleAuth {
    data class Granted(val token: String) : GoogleAuth

    /** Нужно окно Google: выбрать аккаунт и разрешить доступ. */
    data class NeedsUi(val result: AuthorizationResult) : GoogleAuth

    /** [code] — `CommonStatusCodes`; DEVELOPER_ERROR — Google не знает приложение. */
    data class Failed(val code: Int) : GoogleAuth
}

/** Вход Google (D-031; выбор автора — Google Play services): только доступ к Google Tasks. */
object GoogleTasksAuth {
    private val request: AuthorizationRequest
        get() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(GoogleTasksApi.SCOPE))).build()

    /** Попросить доступ; окно показывает вызывающий, если оно нужно. */
    suspend fun silent(context: Context): GoogleAuth = try {
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        val token = result.accessToken
        when {
            result.hasResolution() -> GoogleAuth.NeedsUi(result)
            token != null -> GoogleAuth.Granted(token)
            else -> GoogleAuth.Failed(CommonStatusCodes.ERROR)
        }
    } catch (e: ApiException) {
        GoogleAuth.Failed(e.statusCode)
    }

    /** Ответ окна Google. */
    fun fromIntent(context: Context, data: Intent?): GoogleAuth = try {
        val token = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
        if (token != null) GoogleAuth.Granted(token) else GoogleAuth.Failed(CommonStatusCodes.CANCELED)
    } catch (e: ApiException) {
        GoogleAuth.Failed(e.statusCode)
    }

    suspend fun token(context: Context): String? = (silent(context) as? GoogleAuth.Granted)?.token

    suspend fun clear(context: Context, token: String) {
        try {
            Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        } catch (e: ApiException) {
            // Не сбросился — истечёт сам.
        }
    }
}

/** Ключи сервисов, зашифрованные AES-GCM ключом Android Keystore: файл без Keystore телефона не читается. */
private class TaskVault(private val file: File) {
    fun read(): Map<String, String> = try {
        if (!file.exists()) {
            emptyMap()
        } else {
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            val text = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
            (Json.parse(text) as Map<*, *>).entries.associate { (k, v) -> k as String to v as String }
        }
    } catch (e: GeneralSecurityException) {
        emptyMap()
    } catch (e: IOException) {
        emptyMap()
    } catch (e: IllegalArgumentException) {
        emptyMap()
    }

    fun write(keys: Map<String, String>) {
        if (keys.isEmpty()) {
            file.delete()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(Json.write(keys.toSortedMap()).encodeToByteArray())
        file.parentFile?.mkdirs()
        file.writeBytes(cipher.iv + encrypted)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "dpclock-tasks"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
