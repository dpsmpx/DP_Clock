package com.dpsmpx.dpclock.model

import java.security.MessageDigest

/**
 * Запись файла синхронизации (D-030): что-то одно — будильник, группа, шаблон,
 * исключение, пресет, настройки или города. [data] — тот же объект, что в файле
 * переноса (D-018); null — удалено. [base] — хеш версии, которую эта заменила:
 * по нему другой телефон видит, что его версию уже учли. [from] — имя телефона,
 * который эту версию сделал.
 */
data class SyncItem(val key: String, val data: Map<String, Any?>?, val hash: String, val base: String?, val updatedAt: Long, val from: String = "") {
    val deleted: Boolean get() = data == null
}

/** Общий файл синхронизации — один на все телефоны; кто последний записал и когда. */
data class SyncFile(val writtenBy: String = "", val writtenAt: Long = 0L, val items: Map<String, SyncItem> = emptyMap())

/**
 * Что телефон помнит о синхронизации: [synced] — хеш, на котором телефон и файл в
 * последний раз сошлись; [published] — свои версии записей, с хешем, который они
 * заменили, и временем правки.
 */
data class SyncState(val synced: Map<String, String> = emptyMap(), val published: Map<String, SyncItem> = emptyMap())

/** Изменение, которое синхронизация вносит в базу телефона. */
sealed interface SyncChange {
    val key: String

    data class Put(override val key: String, val data: Map<String, Any?>) : SyncChange

    data class Remove(override val key: String) : SyncChange
}

/** Расхождение (D-030, выбор автора — «Спрашивать»): одно и то же поправили на двух телефонах. */
data class SyncConflict(
    val key: String,
    val local: Map<String, Any?>?,
    val localUpdatedAt: Long,
    val remote: Map<String, Any?>?,
    val remoteUpdatedAt: Long,
    val remoteDevice: String,
)

enum class SyncChoice { LOCAL, REMOTE }

/** Итог прохода; [upload] — файл изменился и его надо записать. */
data class SyncResult(val changes: List<SyncChange>, val conflicts: List<SyncConflict>, val state: SyncState, val file: SyncFile, val upload: Boolean)

/** Слияние телефона с общим файлом (D-030). Чистые функции. */
object SyncRules {
    /** Хеш удалённого. */
    const val DELETED = "deleted"

    /** Сколько помнить об удалённом: дольше телефон вряд ли пролежит без синхронизации. */
    const val TOMBSTONE_DAYS = 90L

    fun hash(data: Map<String, Any?>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Json.write(data).toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /**
     * Один проход: [local] — что сейчас на телефоне (ключ → данные), [remote] — общий
     * файл (null — его ещё нет), [choices] — ответы на расхождения. Возвращает, что
     * внести в базу, какие расхождения спросить, новое состояние и файл для записи.
     * Пока на расхождение не ответили, в файле остаётся чужая версия, на телефоне — своя.
     * [hold] — записи, которые сейчас трогать нельзя (звонящий будильник): они ждут
     * следующего прохода.
     */
    fun run(
        local: Map<String, Map<String, Any?>>,
        state: SyncState,
        remote: SyncFile?,
        deviceName: String,
        now: Long,
        choices: Map<String, SyncChoice> = emptyMap(),
        hold: Set<String> = emptySet(),
    ): SyncResult {
        val published = state.published.toMutableMap()
        // Свои правки и удаления — с версией, которую они заменили.
        for ((key, data) in local) {
            val hash = hash(data)
            if (published[key]?.hash != hash) published[key] = SyncItem(key, data, hash, base = state.synced[key], updatedAt = now, from = deviceName)
        }
        for ((key, old) in state.published) {
            if (key !in local && !old.deleted) published[key] = SyncItem(key, null, DELETED, base = state.synced[key], updatedAt = now, from = deviceName)
        }

        val items = remote?.items.orEmpty()
        val shared = items.toMutableMap()
        val synced = state.synced.toMutableMap()
        val changes = mutableListOf<SyncChange>()
        val conflicts = mutableListOf<SyncConflict>()
        for (key in (published.keys + items.keys).sorted()) {
            if (key in hold) continue
            val mine = published[key]
            val theirs = items[key]
            if (theirs == null) {
                // В файле этого нет — своё туда.
                if (mine != null) {
                    shared[key] = mine
                    synced[key] = mine.hash
                }
                continue
            }
            if (mine == null) {
                // Новое с другого телефона; удалённое, которого здесь и не было, — пропустить.
                if (theirs.data != null) {
                    changes += SyncChange.Put(key, theirs.data)
                    published[key] = theirs
                    synced[key] = theirs.hash
                }
                continue
            }
            if (mine.hash == theirs.hash) {
                synced[key] = theirs.hash
                continue
            }
            val base = synced[key]
            val takeRemote: Boolean? = when {
                // Другой телефон уже видел мою версию — его новее.
                theirs.base == mine.hash -> true
                // Моя версия сделана из той, что в файле: её запись потерялась — записать снова.
                mine.base == theirs.hash -> false
                theirs.hash == base -> false
                mine.hash == base -> true
                else -> choices[key]?.let { it == SyncChoice.REMOTE }
            }
            when (takeRemote) {
                true -> {
                    val data = theirs.data
                    if (data != null) changes += SyncChange.Put(key, data) else if (!mine.deleted) changes += SyncChange.Remove(key)
                    published[key] = theirs
                    synced[key] = theirs.hash
                }
                false -> {
                    // Чужую версию видели: другой телефон возьмёт эту без вопроса.
                    val out = mine.copy(base = theirs.hash)
                    published[key] = out
                    shared[key] = out
                    synced[key] = out.hash
                }
                null -> conflicts += SyncConflict(key, mine.data, mine.updatedAt, theirs.data, theirs.updatedAt, theirs.from)
            }
        }
        val expired = { item: SyncItem -> item.deleted && now - item.updatedAt > TOMBSTONE_DAYS * DAY_MILLIS }
        shared.values.removeAll(expired)
        published.values.removeAll(expired)
        synced.keys.retainAll(published.keys + shared.keys)
        val upload = remote == null || shared != items
        val file = if (remote != null && !upload) remote else SyncFile(deviceName, now, shared)
        return SyncResult(changes, conflicts, SyncState(synced, published), file, upload)
    }

    /** Есть ли на телефоне правки, которых файл ещё не видел: без них читать файл после правки незачем. */
    fun hasLocalChanges(local: Map<String, Map<String, Any?>>, state: SyncState): Boolean =
        local.any { (key, data) -> state.published[key]?.hash != hash(data) } ||
            state.published.any { (key, item) -> !item.deleted && key !in local }

    private const val DAY_MILLIS = 86_400_000L
}

/**
 * Что синхронизируется (D-030, выбор автора — всё): ключи записей и их данные. У
 * будильника не синхронизируются включение и пропуск — они свои у телефона; будильники
 * перед событиями календаря — от календаря этого телефона и не синхронизируются.
 * Свои задачи (D-032) синхронизируются без флажка «В календарь» и события.
 */
object SyncItems {
    const val ALARM = "alarm:"
    const val GROUP = "group:"
    const val TEMPLATE = "template:"
    const val EXCEPTION = "exception:"
    const val PRESET = "preset:"
    const val TASK = "task:"
    const val SETTINGS = "settings"
    const val WORLD_CITIES = "worldCities"

    /** Свои у телефона поля будильника. */
    private val PHONE_FIELDS = setOf("enabled", "skipDate", "skipTime")

    fun snapshot(backup: Backup): Map<String, Map<String, Any?>> = buildMap {
        for (alarm in backup.alarms.filter { it.calendarEventAt == null }) put(ALARM + alarm.id, BackupFormat.alarmMap(alarm) - PHONE_FIELDS)
        for (group in backup.groups) put(GROUP + group.id, BackupFormat.groupMap(group))
        for (template in backup.templates) put(TEMPLATE + template.id, BackupFormat.templateMap(template))
        for (period in backup.exceptions) put(EXCEPTION + period.id, BackupFormat.exceptionMap(period))
        for (preset in backup.presets) put(PRESET + preset.id, BackupFormat.presetMap(preset))
        for (task in backup.tasks) put(TASK + task.id, BackupFormat.taskMap(task))
        backup.settings?.let { put(SETTINGS, BackupFormat.settingsMap(it)) }
        put(WORLD_CITIES, mapOf("cities" to backup.worldCities))
    }

    fun alarm(data: Map<*, *>): Alarm = BackupFormat.alarmFrom(json(data))

    fun group(data: Map<*, *>): AlarmGroup = BackupFormat.groupFrom(json(data))

    fun template(data: Map<*, *>): AlarmTemplate = BackupFormat.templateFrom(json(data))

    fun exception(data: Map<*, *>): ExceptionPeriod = BackupFormat.exceptionFrom(json(data))

    fun preset(data: Map<*, *>): TimerPreset = BackupFormat.presetFrom(json(data))

    /** Своя задача (D-032) — без флажка календаря и события: их ставит этот телефон. */
    fun task(data: Map<*, *>): Task = BackupFormat.taskFrom(json(data))

    /**
     * Настройки. Своя картинка фона — файл этого телефона: «картинка» доходит до
     * другого как есть, и там видна его картинка, а пока её нет — фона нет.
     */
    fun settings(data: Map<*, *>): Settings {
        val settings = BackupFormat.settingsFrom(json(data))
        val image = (data["appearance"] as? Map<*, *>)?.get("background") == ClockBackground.IMAGE.name
        return if (image) settings.copy(appearance = settings.appearance.copy(background = ClockBackground.IMAGE)) else settings
    }

    fun worldCities(data: Map<*, *>): List<String> = (data["cities"] as? List<*>).orEmpty().filterIsInstance<String>().distinct()

    /** Данные как после чтения файла: числа — Long, как их ждёт разбор файла переноса. */
    private fun json(data: Map<*, *>): Map<*, *> = Json.parse(Json.write(data)) as Map<*, *>

    /** id записи без вида: «alarm:abc» → «abc». */
    fun id(key: String): String = key.substringAfter(':')
}

/**
 * Общий файл синхронизации строкой JSON (D-030). Пустой — только что созданный файл;
 * чужой и повреждённый — null: такой файл не перезаписывается.
 */
object SyncFormat {
    const val VERSION = 1
    const val FILE_NAME = "dp-clock-sync.json"
    const val MIME = "application/json"

    fun encode(file: SyncFile, appVersion: String): String = Json.write(
        linkedMapOf(
            "app" to BackupFormat.APP,
            "sync" to VERSION,
            "appVersion" to appVersion,
            "writtenBy" to file.writtenBy,
            "writtenAt" to file.writtenAt,
            "items" to file.items.values.sortedBy { it.key }.map {
                linkedMapOf("key" to it.key, "hash" to it.hash, "base" to it.base, "updatedAt" to it.updatedAt, "from" to it.from, "data" to it.data)
            },
        ),
    )

    fun decode(text: String): SyncFile? {
        if (text.isBlank()) return SyncFile()
        return runCatching {
            val root = Json.parse(text) as Map<*, *>
            if (root["app"] != BackupFormat.APP || (root["sync"] as Long) > VERSION) return null
            val items = (root["items"] as List<*>).map { raw ->
                val item = raw as Map<*, *>
                @Suppress("UNCHECKED_CAST")
                SyncItem(
                    key = item["key"] as String,
                    data = item["data"] as Map<String, Any?>?,
                    hash = item["hash"] as String,
                    base = item["base"] as String?,
                    updatedAt = item["updatedAt"] as Long,
                    from = item["from"] as? String ?: "",
                )
            }
            SyncFile(root["writtenBy"] as? String ?: "", root["writtenAt"] as Long, items.associateBy { it.key })
        }.getOrNull()
    }

    /** Состояние телефона — тоже JSON: свои версии и хеши согласия. */
    fun encodeState(state: SyncState): String = Json.write(
        linkedMapOf(
            "synced" to state.synced.toSortedMap(),
            "published" to encode(SyncFile(items = state.published), ""),
        ),
    )

    fun decodeState(text: String): SyncState = runCatching {
        val root = Json.parse(text) as Map<*, *>
        val synced = (root["synced"] as Map<*, *>).entries.associate { (k, v) -> k as String to v as String }
        SyncState(synced, decode(root["published"] as String)?.items.orEmpty())
    }.getOrDefault(SyncState())
}
