package com.dpsmpx.dpclock.ui

import android.Manifest
import android.content.res.Resources
import android.icu.util.LocaleData
import android.icu.util.ULocale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.data.WeatherProblem
import com.dpsmpx.dpclock.data.WeatherState
import com.dpsmpx.dpclock.model.Intensity
import com.dpsmpx.dpclock.model.Precipitation
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.Sky
import com.dpsmpx.dpclock.model.WeatherMode
import com.dpsmpx.dpclock.model.WeatherPlace
import com.dpsmpx.dpclock.model.WeatherRules
import com.dpsmpx.dpclock.model.WeatherSymbol
import com.dpsmpx.dpclock.model.WorldClockRules
import com.dpsmpx.dpclock.model.Zones
import com.dpsmpx.dpclock.persist
import com.dpsmpx.dpclock.persistLater
import com.dpsmpx.dpclock.platform.NamedZone
import com.dpsmpx.dpclock.platform.PlaceMatch
import com.dpsmpx.dpclock.platform.Places
import com.dpsmpx.dpclock.platform.TimeText
import com.dpsmpx.dpclock.platform.ZoneNames
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** Сколько городов поясов показывать в поиске места для погоды. */
private const val ZONE_MATCHES = 20

/** Пауза после ввода перед поиском через сеть. */
private const val SEARCH_DELAY_MILLIS = 600L

/**
 * «Мировые часы» (D-019): сверху — погода вашего города тихим фоном (выбор
 * автора), ниже — города: время, разница с вашим, день или ночь там. [visible] —
 * раздел на экране: только тогда идёт анимация и спрашивается погода.
 */
@Composable
fun WorldClockScreen(visible: Boolean) {
    val context = LocalContext.current
    val graph = DpClockApp.graph(context)
    val cities by graph.repository.worldCities.collectAsStateWithLifecycle(emptyList())
    val settings by graph.repository.settings.collectAsStateWithLifecycle(Settings())
    val weather by graph.weather.state.collectAsStateWithLifecycle()
    val choice = rememberWeatherChoice()
    var picking by rememberSaveable { mutableStateOf(false) }
    val now = rememberMinute(visible)
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Погода — при открытии раздела (выбор автора) и после смены способа; чаще раза в час сеть не спрашивается.
    // Запрос идёт в области процесса: уход с экрана не обрывает его на середине.
    LaunchedEffect(visible, settings.weather.mode) {
        if (visible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { persist { graph.weather.refresh() } }
    }

    val home = ZoneId.systemDefault()
    val named = remember(cities, LocalConfiguration.current) { cities.mapNotNull { ZoneNames.named(context, it) } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            if (settings.weather.mode != WeatherMode.OFF) {
                item(key = "weather") {
                    WeatherHeader(
                        state = weather.takeIf { it.mode == settings.weather.mode } ?: WeatherState(settings.weather.mode, settings.weather.place),
                        animate = visible,
                        homeZone = home,
                        onOptions = choice.openOptions,
                        onCity = choice.chooseCity,
                        onLocation = choice.useLocation,
                        onOff = choice.turnOff,
                    )
                }
            }
            if (named.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.world_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(named, key = { it.zone.id }) { city ->
                CityRow(city, now, home, first = city == named.first(), last = city == named.last())
            }
        }
        FloatingActionButton(
            onClick = { picking = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(AppIcons.Add, contentDescription = stringResource(R.string.world_add))
        }
    }

    if (picking) {
        CityPickerDialog(
            added = cities.toSet(),
            onDismiss = { picking = false },
            onPick = { zoneId ->
                picking = false
                persistLater { graph.repository.addWorldCity(zoneId) }
            },
        )
    }
}

/** Текущий момент, обновляется к началу каждой минуты, пока раздел виден. */
@Composable
private fun rememberMinute(visible: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(60_000L - now % 60_000L)
            }
        }
    }
    return now
}

@Composable
private fun CityRow(city: NamedZone, now: Long, home: ZoneId, first: Boolean, last: Boolean) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val repository = DpClockApp.graph(context).repository
    val at = Instant.ofEpochMilli(now)
    val zone = ZoneId.of(city.zone.id)
    val day = WorldClockRules.isDaylight(city.zone.latitude, city.zone.longitude, at)
    val shift = WorldClockRules.dayShift(zone, home, at)
    var menu by remember { mutableStateOf(false) }
    val details = listOfNotNull(
        city.country,
        offsetText(resources, WorldClockRules.offsetMinutes(zone, home, at)),
        when (shift) {
            1 -> resources.getString(R.string.world_tomorrow)
            -1 -> resources.getString(R.string.world_yesterday)
            else -> null
        },
    ).joinToString(" · ")
    ListItem(
        leadingContent = {
            Icon(
                if (day) AppIcons.Sun else AppIcons.Moon,
                contentDescription = stringResource(if (day) R.string.world_day else R.string.world_night),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(city.city) },
        supportingContent = { Text(details) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    TimeText.time(context, now, city.zone.id),
                    style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(AppIcons.More, contentDescription = stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (!first) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.move_up)) },
                                onClick = {
                                    menu = false
                                    persistLater { repository.moveWorldCity(city.zone.id, -1) }
                                },
                            )
                        }
                        if (!last) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.move_down)) },
                                onClick = {
                                    menu = false
                                    persistLater { repository.moveWorldCity(city.zone.id, 1) }
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = {
                                menu = false
                                persistLater { repository.removeWorldCity(city.zone.id) }
                            },
                        )
                    }
                }
            }
        },
    )
}

/** Разница с вашим временем: «как у вас», «+3 ч», «−2 ч 30 мин». */
fun offsetText(resources: Resources, minutes: Int): String {
    if (minutes == 0) return resources.getString(R.string.world_same_time)
    val sign = if (minutes > 0) "+" else "−"
    val hours = abs(minutes) / 60
    val rest = abs(minutes) % 60
    return when {
        hours == 0 -> resources.getString(R.string.world_offset_minutes, sign, rest)
        rest == 0 -> resources.getString(R.string.world_offset_hours, sign, hours)
        else -> resources.getString(R.string.world_offset_hours_minutes, sign, hours, rest)
    }
}

/** Белый текст поверх неба — с мягкой тенью. */
private fun TextStyle.onSky(alpha: Float = 1f) =
    copy(color = Color.White.copy(alpha = alpha), shadow = Shadow(Color.Black.copy(alpha = 0.35f), blurRadius = 6f))

@Composable
private fun WeatherHeader(
    state: WeatherState,
    animate: Boolean,
    homeZone: ZoneId,
    onOptions: () -> Unit,
    onCity: () -> Unit,
    onLocation: () -> Unit,
    onOff: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val cache = state.cache
    // Пока погоды нет — небо по времени суток вашего пояса, без осадков.
    val symbol = cache?.weather?.symbol ?: WeatherSymbol(
        Sky.FAIR,
        night = Zones.find(homeZone.id)?.let { !WorldClockRules.isDaylight(it.latitude, it.longitude, Instant.now()) } ?: false,
    )
    Box(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
            // Не ниже 196 dp; крупным шрифтом растёт под текст (D-022, п. 4).
            .heightIn(min = 196.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onOptions)
            .semantics(mergeDescendants = true) {},
    ) {
        WeatherSky(symbol, animate, Modifier.matchParentSize())
        Text(
            stringResource(R.string.weather_credit),
            style = MaterialTheme.typography.labelSmall.onSky(0.7f),
            modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 16.dp, vertical = 12.dp),
        )
        // Сверху отступ под подпись MET Norway: крупным шрифтом текст не наезжает на неё.
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = 44.dp)) {
            if (state.mode == WeatherMode.NOT_SET) {
                Text(stringResource(R.string.weather_prompt_title), style = MaterialTheme.typography.titleLarge.onSky())
                Text(stringResource(R.string.weather_prompt_text), style = MaterialTheme.typography.bodyMedium.onSky(0.9f))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilledTonalButton(onClick = onCity) { Text(stringResource(R.string.weather_city)) }
                    FilledTonalButton(onClick = onLocation) { Text(stringResource(R.string.weather_location)) }
                    TextButton(onClick = onOff) { Text(stringResource(R.string.weather_not_needed), color = Color.White) }
                }
            } else {
                val place = state.place?.name?.takeIf { it.isNotBlank() }
                    ?: stringResource(if (state.mode == WeatherMode.LOCATION) R.string.weather_here else R.string.weather_city)
                Text(place, style = MaterialTheme.typography.titleMedium.onSky())
                if (cache != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(temperatureText(resources, cache.weather.temperature), style = MaterialTheme.typography.displayMedium.onSky())
                        Text(
                            weatherText(resources, cache.weather.symbol),
                            style = MaterialTheme.typography.titleMedium.onSky(0.95f),
                            modifier = Modifier.padding(start = 12.dp, bottom = 10.dp),
                        )
                    }
                }
                Text(
                    statusText(resources, state, cache?.fetchedAt?.let { TimeText.time(context, it) }),
                    style = MaterialTheme.typography.bodySmall.onSky(0.85f),
                )
            }
        }
    }
}

private fun statusText(resources: Resources, state: WeatherState, updated: String?): String {
    val problem = when (state.problem) {
        WeatherProblem.OFFLINE -> resources.getString(R.string.weather_offline)
        WeatherProblem.SERVICE -> resources.getString(R.string.weather_service)
        WeatherProblem.NO_PERMISSION -> resources.getString(R.string.weather_no_permission)
        WeatherProblem.NO_LOCATION -> resources.getString(R.string.weather_no_location)
        null -> null
    }
    val time = when {
        state.loading && updated == null -> resources.getString(R.string.weather_loading)
        updated != null -> resources.getString(R.string.weather_updated, updated)
        else -> null
    }
    return listOfNotNull(problem, time).joinToString(" · ").ifEmpty { resources.getString(R.string.weather_tap_hint) }
}

/** Температура: по-русски «+8°», в США — по Фаренгейту. */
fun temperatureText(resources: Resources, celsius: Double): String {
    val locale = resources.configuration.locales[0]
    if (LocaleData.getMeasurementSystem(ULocale.forLocale(locale)) == LocaleData.MeasurementSystem.US) {
        return resources.getString(R.string.weather_degrees_f, WeatherRules.fahrenheit(celsius).roundToInt())
    }
    val value = celsius.roundToInt()
    return resources.getString(if (value > 0) R.string.weather_degrees_warm else R.string.weather_degrees, value)
}

/** Погода словами: «Переменная облачность», «Небольшой дождь, гроза». */
fun weatherText(resources: Resources, symbol: WeatherSymbol): String {
    val base = when (symbol.precipitation) {
        Precipitation.RAIN -> when (symbol.intensity) {
            Intensity.LIGHT -> R.string.weather_rain_light
            Intensity.MODERATE -> R.string.weather_rain
            Intensity.HEAVY -> R.string.weather_rain_heavy
        }
        Precipitation.SLEET -> when (symbol.intensity) {
            Intensity.LIGHT -> R.string.weather_sleet_light
            Intensity.MODERATE -> R.string.weather_sleet
            Intensity.HEAVY -> R.string.weather_sleet_heavy
        }
        Precipitation.SNOW -> when (symbol.intensity) {
            Intensity.LIGHT -> R.string.weather_snow_light
            Intensity.MODERATE -> R.string.weather_snow
            Intensity.HEAVY -> R.string.weather_snow_heavy
        }
        Precipitation.NONE -> when (symbol.sky) {
            Sky.CLEAR -> R.string.weather_clear
            Sky.FAIR -> R.string.weather_fair
            Sky.PARTLY_CLOUDY -> R.string.weather_partly_cloudy
            Sky.CLOUDY -> R.string.weather_cloudy
            Sky.FOG -> R.string.weather_fog
        }
    }
    val text = resources.getString(base)
    return if (symbol.thunder) resources.getString(R.string.weather_with_thunder, text) else text
}

/** Выбор погоды: свой город, геолокация, без погоды — из шапки и из настроек. */
class WeatherChoice(val openOptions: () -> Unit, val chooseCity: () -> Unit, val useLocation: () -> Unit, val turnOff: () -> Unit)

@Composable
fun rememberWeatherChoice(): WeatherChoice {
    val context = LocalContext.current
    val graph = DpClockApp.graph(context)
    val settings by graph.repository.settings.collectAsStateWithLifecycle(Settings())
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }

    fun setMode(mode: WeatherMode, place: WeatherPlace?) {
        graph.weather.onModeChanged(mode)
        persistLater {
            graph.repository.updateSettings { it.copy(weather = it.weather.copy(mode = mode, place = place)) }
            graph.weather.refresh()
        }
    }

    // Без разрешения шапка скажет об этом; нажатие на неё — снова выбор.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { setMode(WeatherMode.LOCATION, null) }
    val useLocation = {
        dialog = null
        if (Places.hasLocationPermission(context)) setMode(WeatherMode.LOCATION, null)
        else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    when (dialog) {
        "options" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { TitleText(stringResource(R.string.weather_title)) },
            text = {
                Column {
                    WeatherOption(
                        selected = settings.weather.mode == WeatherMode.CITY,
                        title = stringResource(R.string.weather_city),
                        text = settings.weather.place?.name?.takeIf { settings.weather.mode == WeatherMode.CITY },
                        onClick = { dialog = "city" },
                    )
                    WeatherOption(
                        selected = settings.weather.mode == WeatherMode.LOCATION,
                        title = stringResource(R.string.weather_location),
                        text = stringResource(R.string.weather_location_text),
                        onClick = useLocation,
                    )
                    WeatherOption(
                        selected = settings.weather.mode == WeatherMode.OFF,
                        title = stringResource(R.string.weather_off),
                        text = null,
                        onClick = {
                            dialog = null
                            setMode(WeatherMode.OFF, settings.weather.place)
                        },
                    )
                    Text(
                        stringResource(R.string.weather_privacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        "city" -> WeatherPlaceDialog(
            onDismiss = { dialog = null },
            onPick = { place ->
                dialog = null
                setMode(WeatherMode.CITY, place)
            },
        )
    }

    return remember(permission) {
        WeatherChoice(
            openOptions = { dialog = "options" },
            chooseCity = { dialog = "city" },
            useLocation = useLocation,
            turnOff = { setMode(WeatherMode.OFF, null) },
        )
    }
}

@Composable
private fun WeatherOption(selected: Boolean, title: String, text: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title)
            if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Город для погоды: главные города поясов — сразу, любые города — через геокодер телефона. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeatherPlaceDialog(onDismiss: () -> Unit, onPick: (WeatherPlace) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<PlaceMatch>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val zones = remember(LocalConfiguration.current) { ZoneNames.all(context) }
    val key = NamedZone.normalize(query.trim())
    val zoneMatches = if (key.isEmpty()) emptyList() else zones.filter { it.searchKey.contains(key) }.take(ZONE_MATCHES)
    LaunchedEffect(key) {
        found = emptyList()
        if (key.length < 2 || !Places.hasGeocoder()) return@LaunchedEffect
        delay(SEARCH_DELAY_MILLIS)
        searching = true
        found = Places.search(context, query.trim())
        searching = false
    }
    SearchDialog(
        title = stringResource(R.string.weather_city),
        query = query,
        onQuery = { query = it },
        onDismiss = onDismiss,
    ) {
        if (!Places.hasGeocoder()) {
            item { Hint(stringResource(R.string.weather_no_geocoder)) }
        }
        if (found.isNotEmpty()) item { Section(stringResource(R.string.weather_found)) }
        items(found, key = { "f:" + it.place.name + it.place.latitude + it.place.longitude }) { match ->
            ListItem(
                headlineContent = { Text(match.place.name) },
                supportingContent = { if (match.details.isNotEmpty()) Text(match.details) },
                leadingContent = { Icon(AppIcons.Place, contentDescription = null) },
                modifier = Modifier.clickable { onPick(match.place) },
            )
        }
        if (searching) item { Hint(stringResource(R.string.weather_searching)) }
        if (zoneMatches.isNotEmpty()) item { Section(stringResource(R.string.weather_zone_cities)) }
        items(zoneMatches, key = { "z:" + it.zone.id }) { city ->
            ListItem(
                headlineContent = { Text(city.city) },
                supportingContent = { Text(city.country) },
                modifier = Modifier.clickable { onPick(WeatherPlace(city.city, city.zone.latitude, city.zone.longitude)) },
            )
        }
    }
}

/** Город в мировые часы — из главных городов поясов на языке системы. */
@Composable
private fun CityPickerDialog(added: Set<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var query by rememberSaveable { mutableStateOf("") }
    val zones = remember(LocalConfiguration.current) { ZoneNames.all(context) }
    val key = NamedZone.normalize(query.trim())
    val matches = if (key.isEmpty()) zones else zones.filter { it.searchKey.contains(key) }
    val now = Instant.now()
    val home = ZoneId.systemDefault()
    SearchDialog(
        title = stringResource(R.string.world_add),
        query = query,
        onQuery = { query = it },
        onDismiss = onDismiss,
    ) {
        if (matches.isEmpty()) item { Hint(stringResource(R.string.world_nothing_found)) }
        items(matches, key = { it.zone.id }) { city ->
            val isAdded = city.zone.id in added
            val offset = offsetText(resources, WorldClockRules.offsetMinutes(ZoneId.of(city.zone.id), home, now))
            ListItem(
                headlineContent = { Text(city.city) },
                supportingContent = {
                    Text(if (isAdded) city.country + " · " + stringResource(R.string.world_added) else city.country + " · " + offset)
                },
                modifier = if (isAdded) Modifier else Modifier.clickable { onPick(city.zone.id) },
            )
        }
    }
}

/** Окно во весь экран с полем поиска сверху. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchDialog(
    title: String,
    query: String,
    onQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { TitleText(title) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.cancel)) }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.world_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(Modifier.fillMaxSize(), content = content)
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Подпись режима погоды для настроек. */
@Composable
fun weatherModeText(mode: WeatherMode, place: WeatherPlace?): String = when (mode) {
    WeatherMode.NOT_SET -> stringResource(R.string.weather_not_set)
    WeatherMode.OFF -> stringResource(R.string.weather_off)
    WeatherMode.CITY -> place?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.weather_city)
    WeatherMode.LOCATION -> stringResource(R.string.weather_location)
}
