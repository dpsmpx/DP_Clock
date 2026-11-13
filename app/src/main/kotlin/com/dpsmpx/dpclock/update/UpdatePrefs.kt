package com.dpsmpx.dpclock.update

import android.content.Context
import androidx.core.content.edit
import com.dpsmpx.dpclock.data.deviceStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Настройки проверки обновлений — только на этом телефоне (SharedPreferences),
 * не входят в файл переноса и синхронизацию: у каждого устройства своя версия APK.
 */
class UpdatePrefs private constructor(context: Context) {
    private val prefs = context.deviceStorage().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    /** Раз в сутки спрашивать у GitHub, не вышла ли новая версия. По умолчанию — да. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, value) }
        _enabled.value = value
        if (!value) setAvailable(null)
    }

    var lastCheckAt: Long
        get() = prefs.getLong(KEY_CHECKED_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_CHECKED_AT, value) }

    private val _available = MutableStateFlow(loadAvailable())
    /** Найденная новая версия; null — нет или скрыта. */
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    private fun loadAvailable(): UpdateInfo? {
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        val url = prefs.getString(KEY_URL, null) ?: return null
        if (version == prefs.getString(KEY_DISMISSED, null)) return null
        return UpdateInfo(version, url)
    }

    /** @param showAgain пользователь сам нажал «Проверить» — показываем и скрытую ранее версию. */
    fun setAvailable(update: UpdateInfo?, showAgain: Boolean = false) {
        prefs.edit {
            putString(KEY_VERSION, update?.version)
            putString(KEY_URL, update?.url)
            if (showAgain) remove(KEY_DISMISSED)
        }
        _available.value = loadAvailable()
    }

    /** «Скрыть»: об этой версии больше не напоминаем, о следующей — напомним. */
    fun dismiss() {
        val version = _available.value?.version ?: return
        prefs.edit { putString(KEY_DISMISSED, version) }
        _available.value = null
    }

    companion object {
        private const val PREFS = "update"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CHECKED_AT = "checked_at"
        private const val KEY_VERSION = "version"
        private const val KEY_URL = "url"
        private const val KEY_DISMISSED = "dismissed"

        @Volatile
        private var instance: UpdatePrefs? = null

        fun get(context: Context): UpdatePrefs =
            instance ?: synchronized(this) {
                instance ?: UpdatePrefs(context.applicationContext).also { instance = it }
            }
    }
}
