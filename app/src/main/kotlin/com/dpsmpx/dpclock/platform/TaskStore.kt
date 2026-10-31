package com.dpsmpx.dpclock.platform

import android.content.Context
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.dpsmpx.dpclock.BuildConfig
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.data.ClockRepository
import com.dpsmpx.dpclock.model.DayTask
import com.dpsmpx.dpclock.model.GoogleOAuth
import com.dpsmpx.dpclock.model.GoogleTasksApi
import com.dpsmpx.dpclock.model.Json
import com.dpsmpx.dpclock.model.Task
import com.dpsmpx.dpclock.model.TaskCache
import com.dpsmpx.dpclock.model.TaskFormatException
import com.dpsmpx.dpclock.model.TaskRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalDateTime
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Что видят экраны (D-032): задачи Google и выбор показа. [refreshing] — идёт обновление. */
data class TaskState(val cache: TaskCache = TaskCache(), val refreshing: Boolean = false)

/** Задачи дня: свои и Google, сегодня и просроченные. */
fun dayTasks(own: List<Task>, cache: TaskCache, today: LocalDate): List<DayTask> =
    TaskRules.forDay(own.mapNotNull(TaskRules::day) + if (cache.google) cache.tasks else emptyList(), today)

/** Итог входа Google: удалось или почему нет — словами Google («access_denied», «invalid_client: …»). */
sealed interface GoogleSignIn {
    data object Done : GoogleSignIn

    data class Failed(val reason: String) : GoogleSignIn
}

/**
 * Задачи (D-032): свои — в базе ([ClockRepository]), здесь — Google Tasks и показ.
 * Вход Google — в браузере (выбор автора): OAuth для установленных приложений,
 * возврат на `http://127.0.0.1:<порт>` этого телефона, PKCE. Клиент, секрет и ключ
 * обновления — зашифрованы ключом Android Keystore, только на этом телефоне, вне
 * резервной копии, переноса и синхронизации. В сеть — только к Google и только когда
 * открыты «Часы», после утреннего будильника и из настроек.
 */
class TaskStore(private val context: Context, private val notifier: Notifier, private val repository: ClockRepository) {
    /** Хранилище учётных данных: файлы открываются только после разблокировки. */
    private val dir = context.noBackupFilesDir
    private val cacheFile = File(dir, "tasks.json")
    private val vault = TaskVault(File(dir, "tasks-keys.bin"))
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(TaskState(readCache() ?: TaskCache()))
    private var loaded = mutableState.value.cache != TaskCache()

    @Volatile
    private var access: Pair<String, Long>? = null

    @Volatile
    private var signInServer: ServerSocket? = null

    @Volatile
    private var cleaned = false

    val state: StateFlow<TaskState> = mutableState

    /** Состояние сейчас; создан до разблокировки — после неё перечитывает кэш (виджет после перезагрузки). */
    fun current(): TaskState {
        if (!loaded && unlocked()) {
            readCache()?.let { cache -> mutableState.update { it.copy(cache = cache) } }
            loaded = true
        }
        return mutableState.value
    }

    /** Обновить задачи Google; [force] — даже если обновляли только что. */
    suspend fun refresh(force: Boolean = false): Boolean = mutex.withLock {
        current()
        if (!cleaned && unlocked()) {
            // Ключи Todoist и TickTick из 0.18.0 больше не нужны — с телефона их долой.
            withContext(Dispatchers.IO) {
                val keys = vault.read()
                val kept = keys.filterKeys { it in setOf(CLIENT_ID, SECRET, REFRESH) }
                if (kept != keys) vault.write(kept)
            }
            cleaned = true
        }
        val cache = mutableState.value.cache
        if (!cache.google || !unlocked()) return@withLock false
        val now = System.currentTimeMillis()
        if (!force && now - cache.fetchedAt < TaskRules.REFRESH_MILLIS) return@withLock false
        mutableState.update { it.copy(refreshing = true) }
        try {
            val result = fetch(LocalDate.now())
            val latest = mutableState.value.cache
            save(
                when (result) {
                    is Fetched.Done -> latest.copy(tasks = result.tasks, failure = null, fetchedAt = now)
                    is Fetched.Failed -> latest.copy(failure = result.code, fetchedAt = now)
                },
            )
            true
        } finally {
            mutableState.update { it.copy(refreshing = false) }
        }
    }

    /** «Сделано» у задачи Google: сразу убрать с экрана, ошибка — вернуть. */
    suspend fun completeGoogle(task: DayTask): Boolean {
        val same = { other: DayTask -> other.id == task.id && other.listId == task.listId }
        mutableState.update { it.copy(cache = it.cache.copy(tasks = it.cache.tasks.filterNot(same))) }
        return mutex.withLock {
            save(mutableState.value.cache.copy(tasks = mutableState.value.cache.tasks.filterNot(same)))
            val code = google { token ->
                http("POST", GoogleTasksApi.taskUrl(task.listId, task.id), token, GoogleTasksApi.COMPLETE_BODY, override = "PATCH")
            }.code
            val done = code in 200..299
            if (!done) save(mutableState.value.cache.let { it.copy(tasks = it.tasks + task) })
            done
        }
    }

    /**
     * Вход Google в браузере. [open] открывает страницу Google; ответ браузер приносит
     * на 127.0.0.1 — ждём его не дольше [SIGN_IN_MILLIS]. Потом — ключи по коду и
     * первые задачи.
     */
    suspend fun signIn(clientId: String, secret: String, open: suspend (String) -> Unit): GoogleSignIn {
        if (!unlocked()) return GoogleSignIn.Failed("locked")
        val redirect: GoogleOAuth.Redirect
        val redirectUri: String
        val verifier = GoogleOAuth.verifier(random(32))
        val state = GoogleOAuth.verifier(random(16))
        val server = try {
            withContext(Dispatchers.IO) { ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)) }
        } catch (e: IOException) {
            return GoogleSignIn.Failed(e.message ?: "socket")
        }
        signInServer = server
        try {
            redirectUri = "http://$LOOPBACK:${server.localPort}"
            open(GoogleOAuth.authUrl(clientId, redirectUri, GoogleOAuth.challenge(verifier), state))
            redirect = withContext(Dispatchers.IO) { awaitRedirect(server, state) } ?: return GoogleSignIn.Failed("timeout")
        } finally {
            signInServer = null
            withContext(Dispatchers.IO) { runCatching { server.close() } }
        }
        redirect.error?.let { return GoogleSignIn.Failed(it) }
        if (redirect.state != state) return GoogleSignIn.Failed("state")
        val code = redirect.code ?: return GoogleSignIn.Failed("no code")
        val response = form(GoogleOAuth.TOKEN_URL, GoogleOAuth.codeBody(clientId, secret, code, verifier, redirectUri))
        if (response.code != 200) return GoogleSignIn.Failed("HTTP ${response.code} " + GoogleOAuth.parseError(response.error))
        val tokens = try {
            GoogleOAuth.parseTokens(response.text)
        } catch (e: TaskFormatException) {
            return GoogleSignIn.Failed("token")
        }
        val refresh = tokens.refresh ?: return GoogleSignIn.Failed("no refresh_token")
        withContext(Dispatchers.IO) { vault.write(mapOf(CLIENT_ID to clientId, SECRET to secret, REFRESH to refresh)) }
        access = tokens.access to System.currentTimeMillis() + (tokens.expiresIn - 60) * 1000
        mutex.withLock { save(mutableState.value.cache.copy(google = true, failure = null)) }
        refresh(force = true)
        return GoogleSignIn.Done
    }

    /** Окно входа закрыли — перестать ждать браузер. */
    fun cancelSignIn() {
        runCatching { signInServer?.close() }
    }

    /** Отключить Google: доступ отзывается у Google, ключи и задачи удаляются с телефона. */
    suspend fun disconnect() = mutex.withLock {
        val keys = withContext(Dispatchers.IO) { vault.read() }
        keys[REFRESH]?.let { form(GoogleOAuth.REVOKE_URL, GoogleOAuth.revokeBody(it)) }
        withContext(Dispatchers.IO) { vault.write(emptyMap()) }
        access = null
        save(mutableState.value.cache.copy(google = false, tasks = emptyList(), failure = null))
    }

    /** Где показывать: «Часы», после будильника, виджет. */
    suspend fun updateShow(change: (TaskCache) -> TaskCache) = mutex.withLock { save(change(mutableState.value.cache)) }

    /**
     * Утренний будильник выключен (выбор автора — «После будильника»): свежие задачи и
     * уведомление «Задачи на сегодня» — свои и Google, один раз в день.
     */
    suspend fun afterAlarm() {
        val cache = current().cache
        if (!cache.showAfterAlarm) return
        val now = LocalDateTime.now()
        if (!TaskRules.isMorning(now.toLocalTime()) || cache.notifiedOn == now.toLocalDate()) return
        refresh(force = true)
        val tasks = dayTasks(repository.currentTasks(), mutableState.value.cache, now.toLocalDate())
        mutex.withLock { save(mutableState.value.cache.copy(notifiedOn = now.toLocalDate())) }
        if (tasks.isNotEmpty()) notifier.tasks(tasks)
    }

    /** Для снимков экранов: задачи без сети и ключей. */
    internal fun preview(cache: TaskCache) {
        loaded = true
        mutableState.value = TaskState(cache.copy(fetchedAt = System.currentTimeMillis()))
    }

    private sealed interface Fetched {
        data class Done(val tasks: List<DayTask>) : Fetched

        data class Failed(val code: Int) : Fetched
    }

    private suspend fun fetch(today: LocalDate): Fetched {
        try {
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
            return Fetched.Done(tasks)
        } catch (e: TaskFormatException) {
            return Fetched.Failed(FORMAT)
        }
    }

    /** Запрос к Google с ключом доступа; истёк (401) — новый по ключу обновления, один раз. */
    private suspend fun google(call: suspend (String) -> Response): Response {
        val token = token(renew = false) ?: return Response(TaskCache.SIGN_IN)
        val first = call(token)
        if (first.code != 401) return first
        val again = token(renew = true) ?: return Response(TaskCache.SIGN_IN)
        return call(again)
    }

    /** Ключ доступа: прежний, пока не истёк, иначе — по ключу обновления. Вход отозван — null. */
    private suspend fun token(renew: Boolean): String? {
        access?.takeIf { !renew && it.second > System.currentTimeMillis() }?.let { return it.first }
        val keys = withContext(Dispatchers.IO) { vault.read() }
        val refresh = keys[REFRESH] ?: return null
        val response = form(GoogleOAuth.TOKEN_URL, GoogleOAuth.refreshBody(keys[CLIENT_ID].orEmpty(), keys[SECRET].orEmpty(), refresh))
        if (response.code != 200) return null
        val tokens = try {
            GoogleOAuth.parseTokens(response.text)
        } catch (e: TaskFormatException) {
            return null
        }
        access = tokens.access to System.currentTimeMillis() + (tokens.expiresIn - 60) * 1000
        return tokens.access
    }

    /** Ждать, пока браузер придёт на 127.0.0.1 с ответом Google; лишнее (значок сайта) — мимо. */
    private fun awaitRedirect(server: ServerSocket, state: String): GoogleOAuth.Redirect? {
        server.soTimeout = SIGN_IN_MILLIS
        repeat(MAX_REQUESTS) {
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                return null
            } catch (e: SocketException) {
                return null
            }
            socket.use {
                socket.soTimeout = REQUEST_MILLIS
                val line = socket.getInputStream().bufferedReader().readLine().orEmpty()
                val redirect = GoogleOAuth.parseRedirect(line.split(' ').getOrNull(1).orEmpty())
                val ours = redirect.state != null && (redirect.code != null || redirect.error != null)
                val ok = ours && redirect.error == null && redirect.state == state
                val body = if (ours) page(ok) else ""
                val head = if (ours) "HTTP/1.1 200 OK" else "HTTP/1.1 404 Not Found"
                val bytes = body.encodeToByteArray()
                socket.getOutputStream().apply {
                    write("$head\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                    write(bytes)
                    flush()
                }
                if (ours) return redirect
            }
        }
        return null
    }

    /** Страница в браузере после входа: итог и ссылка назад в приложение. */
    private fun page(ok: Boolean): String {
        val title = context.getString(if (ok) R.string.tasks_google_page_done else R.string.tasks_google_page_failed)
        val back = context.getString(R.string.tasks_google_page_back)
        val link = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=${context.packageName};end"
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
            "<title>DP Clock</title></head><body style=\"font-family:sans-serif;text-align:center;padding:48px 16px\">" +
            "<h2>${html(title)}</h2><p><a href=\"$link\" style=\"font-size:20px\">${html(back)}</a></p></body></html>"
    }

    private fun html(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private class Response(val code: Int, val text: String = "", val error: String = "")

    /** POST формы (ключи Google). */
    private suspend fun form(url: String, body: String): Response = http("POST", url, null, body, contentType = "application/x-www-form-urlencoded")

    /** HTTPS к Google; [override] — метод, которого нет у `HttpURLConnection` (PATCH). Нет связи — код 0. */
    private suspend fun http(
        method: String,
        url: String,
        token: String?,
        body: String? = null,
        override: String? = null,
        contentType: String = "application/json; charset=utf-8",
    ): Response = withContext(Dispatchers.IO) {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return@withContext Response(0)
        }
        try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.requestMethod = method
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "DP_Clock/" + BuildConfig.VERSION_NAME)
            override?.let { connection.setRequestProperty("X-HTTP-Method-Override", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { it.write(body.encodeToByteArray()) }
            }
            val code = connection.responseCode
            if (code in 200..299) {
                Response(code, connection.inputStream.use { read(it) })
            } else {
                Response(code, error = connection.errorStream?.use { read(it) }.orEmpty())
            }
        } catch (e: IOException) {
            Response(0)
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

    private fun random(bytes: Int): ByteArray = ByteArray(bytes).also { SecureRandom().nextBytes(it) }

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
        const val LOOPBACK = "127.0.0.1"
        const val TIMEOUT_MILLIS = 15_000
        const val SIGN_IN_MILLIS = 5 * 60_000
        const val REQUEST_MILLIS = 10_000
        const val MAX_REQUESTS = 8
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_PAGES = 5

        /** Ключи в хранилище; ключи Todoist и TickTick из 0.18.0 удаляются при первом обновлении. */
        const val CLIENT_ID = "googleClientId"
        const val SECRET = "googleSecret"
        const val REFRESH = "googleRefresh"

        /** Свой код отказа рядом с кодами HTTP. */
        const val FORMAT = -3
    }
}

/** Ключи, зашифрованные AES-GCM ключом Android Keystore: файл без Keystore телефона не читается. */
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
