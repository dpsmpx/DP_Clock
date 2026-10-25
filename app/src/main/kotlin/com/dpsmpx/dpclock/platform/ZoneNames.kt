package com.dpsmpx.dpclock.platform

import android.content.Context
import android.icu.text.TimeZoneNames
import android.icu.util.ULocale
import com.dpsmpx.dpclock.model.Zone
import com.dpsmpx.dpclock.model.Zones
import java.util.Locale

/** Пояс с названиями на языке системы: город — из ICU, страна — по коду ISO. */
data class NamedZone(val zone: Zone, val city: String, val country: String) {
    /** Строка для поиска: город, страна и сам пояс, без регистра и «ё». */
    val searchKey: String = normalize("$city $country ${zone.id.replace('_', ' ')}")

    companion object {
        fun normalize(text: String): String = text.lowercase().replace('ё', 'е')
    }
}

/** Названия городов мировых часов (D-019): данные ICU в Android — свои для каждого языка, без сети. */
object ZoneNames {
    @Volatile
    private var cache: Pair<Locale, List<NamedZone>>? = null

    /** Все пояса с названиями на языке [context], по названию города. */
    fun all(context: Context): List<NamedZone> {
        val locale = context.resources.configuration.locales[0]
        cache?.takeIf { it.first == locale }?.let { return it.second }
        val names = TimeZoneNames.getInstance(ULocale.forLocale(locale))
        val list = Zones.all.map { NamedZone(it, cityOf(names, it.id), countryOf(it.country, locale)) }
            .sortedBy { NamedZone.normalize(it.city) }
        cache = locale to list
        return list
    }

    fun city(context: Context, zoneId: String): String {
        val locale = context.resources.configuration.locales[0]
        return cityOf(TimeZoneNames.getInstance(ULocale.forLocale(locale)), zoneId)
    }

    fun named(context: Context, zoneId: String): NamedZone? = all(context).firstOrNull { it.zone.id == zoneId }

    private fun cityOf(names: TimeZoneNames, zoneId: String): String =
        names.getExemplarLocationName(zoneId)?.takeIf { it.isNotBlank() } ?: zoneId.substringAfterLast('/').replace('_', ' ')

    private fun countryOf(code: String, locale: Locale): String =
        runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(locale) }.getOrDefault(code)
}
