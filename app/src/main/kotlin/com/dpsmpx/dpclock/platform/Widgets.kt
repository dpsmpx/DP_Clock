package com.dpsmpx.dpclock.platform

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.MainActivity
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.Section
import com.dpsmpx.dpclock.data.SurfaceData
import com.dpsmpx.dpclock.model.AlarmRules
import com.dpsmpx.dpclock.model.TimerState
import com.dpsmpx.dpclock.model.WeatherMode
import com.dpsmpx.dpclock.model.WorldClockRules
import com.dpsmpx.dpclock.ui.offsetText
import com.dpsmpx.dpclock.ui.repeatText
import com.dpsmpx.dpclock.ui.temperatureText
import com.dpsmpx.dpclock.ui.weatherText
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle

/** Виджет «Часы и ближайший будильник» (D-020). */
class ClockWidget : DpClockWidget()

/** Виджет «Будильники с переключателями» (D-020). */
class AlarmsWidget : DpClockWidget()

/** Виджет «Таймеры»: идущие и пресеты одним нажатием (D-020). */
class TimersWidget : DpClockWidget()

/** Виджет «Мировые часы и погода» (D-020): погода — из кэша, без запросов в фоне. */
class WorldWidget : DpClockWidget()

/** Общий провайдер: любое обновление системы перерисовывает все виджеты DP_Clock. */
open class DpClockWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        DpClockApp.scope.launch {
            try {
                Widgets.updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Виджеты на `RemoteViews` (D-020): время, дата и остаток таймера идут сами
 * (`TextClock`, `Chronometer`) — приложение будится, только когда меняются данные.
 */
object Widgets {
    private const val REQUEST_LIST = 100

    suspend fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = mapOf(
            ClockWidget::class.java to manager.ids(context, ClockWidget::class.java),
            AlarmsWidget::class.java to manager.ids(context, AlarmsWidget::class.java),
            TimersWidget::class.java to manager.ids(context, TimersWidget::class.java),
            WorldWidget::class.java to manager.ids(context, WorldWidget::class.java),
        ).filterValues { it.isNotEmpty() }
        if (ids.isEmpty()) return
        val data = DpClockApp.graph(context).repository.surfaceData()
        runCatching {
            ids[ClockWidget::class.java]?.let { manager.updateAppWidget(it, clock(context, data)) }
            ids[AlarmsWidget::class.java]?.let { manager.updateAppWidget(it, alarms(context, data)) }
            ids[TimersWidget::class.java]?.let { manager.updateAppWidget(it, timers(context, data)) }
            ids[WorldWidget::class.java]?.let { manager.updateAppWidget(it, world(context, data)) }
        }
    }

    private fun AppWidgetManager.ids(context: Context, provider: Class<*>): IntArray =
        runCatching { getAppWidgetIds(ComponentName(context, provider)) }.getOrDefault(IntArray(0))

    private fun open(context: Context, section: Section): PendingIntent = PendingIntent.getActivity(
        context,
        200 + section.ordinal,
        MainActivity.intent(context, section),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Шаблон нажатий в списке: кнопки строк дополняют его своим действием (fill-in). */
    private fun listTemplate(context: Context, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_LIST + code,
        Intent(context, ActionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** Ближайший будильник словами: «Сегодня, 7:00», «Завтра, 7:00», «пн, 7:00». */
    fun nextAlarmText(context: Context, data: SurfaceData, now: Instant = Instant.now()): String? {
        val zone = ZoneId.systemDefault()
        val (_, at) = AlarmRules.nearestAlarm(data.alarms, now, zone, data.exceptions) ?: return null
        val time = TimeText.time(context, at.toEpochMilli())
        val today = now.atZone(zone).toLocalDate()
        val day = at.atZone(zone).toLocalDate()
        val resources = context.resources
        return when (day) {
            today -> resources.getString(R.string.widget_alarm_today, time)
            today.plusDays(1) -> resources.getString(R.string.widget_alarm_tomorrow, time)
            else -> resources.getString(
                R.string.widget_alarm_day,
                day.dayOfWeek.getDisplayName(TextStyle.SHORT, resources.configuration.locales[0]),
                time,
            )
        }
    }

    private fun clock(context: Context, data: SurfaceData): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_clock).apply {
            setTextViewText(R.id.widget_alarm, nextAlarmText(context, data) ?: context.getString(R.string.widget_no_alarms))
            setOnClickPendingIntent(android.R.id.background, open(context, Section.CLOCK))
            setOnClickPendingIntent(R.id.widget_alarm, open(context, Section.ALARMS))
        }

    private fun alarms(context: Context, data: SurfaceData): RemoteViews {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val sorted = AlarmRules.sorted(data.alarms, data.settings.alarmOrder, now, zone, data.exceptions)
        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        for (alarm in sorted) {
            val row = RemoteViews(context.packageName, R.layout.widget_alarm_item).apply {
                setTextViewText(R.id.item_title, TimeText.time(context, alarm.hour, alarm.minute))
                setTextViewText(
                    R.id.item_text,
                    listOf(alarm.label, repeatText(context, alarm.repeat)).filter { it.isNotBlank() }.joinToString(" · "),
                )
                setCompoundButtonChecked(R.id.item_switch, alarm.enabled)
                setOnCheckedChangeResponse(
                    R.id.item_switch,
                    RemoteViews.RemoteResponse.fromFillInIntent(
                        Intent(ActionReceiver.ACTION_ALARM_TOGGLE).putExtra(ActionReceiver.EXTRA_ID, alarm.id),
                    ),
                )
                setContentDescription(R.id.item_switch, context.getString(R.string.widget_toggle_alarm, TimeText.time(context, alarm.hour, alarm.minute)))
            }
            items.addItem(alarm.id.hashCode().toLong(), row)
        }
        return list(context, R.string.section_alarms, Section.ALARMS, items.build(), R.string.widget_alarms_empty, code = 1)
    }

    private fun timers(context: Context, data: SurfaceData): RemoteViews {
        val now = Clocks.moment()
        val resources = context.resources
        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(2)
        for (run in data.runs) {
            val row = RemoteViews(context.packageName, R.layout.widget_timer_item).apply {
                setTextViewText(R.id.item_title, run.name.ifBlank { resources.getString(R.string.journal_timer) })
                val remaining = run.remainingAt(now)
                when (run.state) {
                    TimerState.RUNNING -> {
                        setViewVisibility(R.id.item_chronometer, View.VISIBLE)
                        setViewVisibility(R.id.item_value, View.GONE)
                        setChronometer(R.id.item_chronometer, SystemClock.elapsedRealtime() + remaining, null, true)
                        setChronometerCountDown(R.id.item_chronometer, true)
                        setTextViewText(R.id.item_text, resources.getString(R.string.widget_timer_tap_pause))
                    }
                    TimerState.PAUSED, TimerState.RINGING -> {
                        setViewVisibility(R.id.item_chronometer, View.GONE)
                        setViewVisibility(R.id.item_value, View.VISIBLE)
                        setTextViewText(R.id.item_value, TimeText.countdown(remaining))
                        setTextViewText(
                            R.id.item_text,
                            resources.getString(if (run.state == TimerState.PAUSED) R.string.widget_timer_paused else R.string.ring_timer_finished),
                        )
                    }
                }
                if (run.state != TimerState.RINGING) {
                    val action = if (run.state == TimerState.RUNNING) ActionReceiver.ACTION_TIMER_PAUSE else ActionReceiver.ACTION_TIMER_RESUME
                    setOnClickFillInIntent(R.id.item_root, Intent(action).putExtra(ActionReceiver.EXTRA_ID, run.id))
                }
            }
            items.addItem(("run:" + run.id).hashCode().toLong(), row)
        }
        for (preset in data.presets) {
            val row = RemoteViews(context.packageName, R.layout.widget_preset_item).apply {
                setTextViewText(R.id.item_title, preset.name)
                setTextViewText(R.id.item_value, TimeText.duration(preset.program.totalMillis))
                setOnClickFillInIntent(R.id.item_root, Intent(ActionReceiver.ACTION_PRESET_START).putExtra(ActionReceiver.EXTRA_ID, preset.id))
                setContentDescription(R.id.item_root, resources.getString(R.string.widget_start_preset, preset.name))
            }
            items.addItem(("preset:" + preset.id).hashCode().toLong(), row)
        }
        return list(context, R.string.section_timers, Section.TIMERS, items.build(), R.string.widget_timers_empty, code = 2)
    }

    private fun world(context: Context, data: SurfaceData): RemoteViews {
        val now = Instant.now()
        val home = ZoneId.systemDefault()
        val resources = context.resources
        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        for (zoneId in data.worldCities) {
            val city = ZoneNames.named(context, zoneId) ?: continue
            val zone = ZoneId.of(zoneId)
            val day = WorldClockRules.isDaylight(city.zone.latitude, city.zone.longitude, now)
            val shift = when (WorldClockRules.dayShift(zone, home, now)) {
                1 -> resources.getString(R.string.world_tomorrow)
                -1 -> resources.getString(R.string.world_yesterday)
                else -> null
            }
            val row = RemoteViews(context.packageName, R.layout.widget_city_item).apply {
                setImageViewResource(R.id.item_icon, if (day) R.drawable.ic_widget_sun else R.drawable.ic_widget_moon)
                setTextViewText(R.id.item_title, city.city)
                setTextViewText(
                    R.id.item_text,
                    listOfNotNull(offsetText(resources, WorldClockRules.offsetMinutes(zone, home, now)), shift).joinToString(" · "),
                )
                setString(R.id.item_clock, "setTimeZone", zoneId)
            }
            items.addItem(zoneId.hashCode().toLong(), row)
        }
        val views = list(context, R.string.section_world, Section.WORLD, items.build(), R.string.widget_world_empty, code = 3)
        val weather = DpClockApp.graph(context).weather.cached()
        val mode = data.settings.weather.mode
        if (weather != null && (mode == WeatherMode.CITY || mode == WeatherMode.LOCATION)) {
            val place = data.settings.weather.place?.name.orEmpty()
            val text = listOf(place, temperatureText(resources, weather.weather.temperature), weatherText(resources, weather.weather.symbol))
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            views.setTextViewText(R.id.widget_subtitle, text)
            views.setViewVisibility(R.id.widget_subtitle, View.VISIBLE)
        }
        return views
    }

    private fun list(
        context: Context,
        title: Int,
        section: Section,
        items: RemoteViews.RemoteCollectionItems,
        empty: Int,
        code: Int,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_list).apply {
        setTextViewText(R.id.widget_title, context.getString(title))
        setOnClickPendingIntent(R.id.widget_title, open(context, section))
        setOnClickPendingIntent(R.id.widget_empty, open(context, section))
        setTextViewText(R.id.widget_empty, context.getString(empty))
        setRemoteAdapter(R.id.widget_items, items)
        setEmptyView(R.id.widget_items, R.id.widget_empty)
        setPendingIntentTemplate(R.id.widget_items, listTemplate(context, code))
    }
}
