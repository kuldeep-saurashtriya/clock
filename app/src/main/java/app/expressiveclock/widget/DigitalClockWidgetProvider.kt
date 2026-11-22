package app.expressiveclock.widget

import android.Manifest
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import app.expressiveclock.data.ClockStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DigitalClockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        background(context) { store ->
            ids.forEach { DigitalClockWidgetRenderer.update(context, manager, it) }
            store.sweepWidgetConfigs(ClockWidgetProvider.allPlacedWidgetIds(context))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        background(context) { DigitalClockWidgetRenderer.update(context, manager, appWidgetId) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        background(context) { it.deleteWidgetConfigs(ids) }
    }

    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        background(context) { store ->
            store.remapWidgetConfigs(oldIds, newIds)
            val manager = AppWidgetManager.getInstance(context)
            newIds.forEach { DigitalClockWidgetRenderer.update(context, manager, it) }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WEATHER_TAP -> {
                val now = SystemClock.elapsedRealtime()
                val isDoubleTap = (now - lastWeatherTapElapsed) <= DOUBLE_TAP_WINDOW_MS
                lastWeatherTapElapsed = now

                if (isDoubleTap) {
                    lastWeatherTapElapsed = 0L
                    pendingWeatherSingleTapJob?.cancel()
                    background(context) {
                        coroutineScope {
                            val manager = AppWidgetManager.getInstance(context)
                            val ids = manager.getAppWidgetIds(component(context))
                            ids.forEach {
                                DigitalClockWidgetRenderer.update(
                                    context,
                                    manager,
                                    it,
                                    weatherOverride = "ICON:LOADING|D|...",
                                )
                            }
                            val badgeDeferred = async {
                                WidgetWeatherHelper.getWeatherBadge(context, forceRefresh = true)
                            }
                            delay(1150L)
                            val freshBadge = badgeDeferred.await()
                            ids.forEach {
                                DigitalClockWidgetRenderer.update(
                                    context,
                                    manager,
                                    it,
                                    weatherOverride = freshBadge,
                                )
                            }
                        }
                    }
                } else {
                    pendingWeatherSingleTapJob?.cancel()
                    val app = context.applicationContext
                    pendingWeatherSingleTapJob = CoroutineScope(Dispatchers.Main.immediate).launch {
                        delay(DOUBLE_TAP_WINDOW_MS)
                        if (lastWeatherTapElapsed != 0L) {
                            lastWeatherTapElapsed = 0L
                            openDefaultWeatherApp(app)
                        }
                    }
                }
            }

            ACTION_DATE_TAP -> {
                val now = SystemClock.elapsedRealtime()
                val isDoubleTap = (now - lastDateTapElapsed) <= DOUBLE_TAP_WINDOW_MS
                lastDateTapElapsed = now

                if (isDoubleTap) {
                    lastDateTapElapsed = 0L
                    pendingDateSingleTapJob?.cancel()
                    dateOverlayResetJob?.cancel()

                    val app = context.applicationContext
                    if (isShowingCalendarEvent) {
                        isShowingCalendarEvent = false
                        background(app) {
                            val manager = AppWidgetManager.getInstance(app)
                            manager.getAppWidgetIds(component(app)).forEach {
                                DigitalClockWidgetRenderer.update(app, manager, it, dateOverride = null)
                            }
                        }
                    } else {
                        isShowingCalendarEvent = true
                        background(app) {
                            val summary = queryNextCalendarEvent(app)
                            val manager = AppWidgetManager.getInstance(app)
                            val ids = manager.getAppWidgetIds(component(app))
                            ids.forEach {
                                DigitalClockWidgetRenderer.update(app, manager, it, dateOverride = summary)
                            }
                        }
                        dateOverlayResetJob = CoroutineScope(Dispatchers.Default).launch {
                            delay(8_000L)
                            if (isShowingCalendarEvent) {
                                isShowingCalendarEvent = false
                                val manager = AppWidgetManager.getInstance(app)
                                manager.getAppWidgetIds(component(app)).forEach {
                                    DigitalClockWidgetRenderer.update(app, manager, it, dateOverride = null)
                                }
                            }
                        }
                    }
                } else {
                    pendingDateSingleTapJob?.cancel()
                    val app = context.applicationContext
                    pendingDateSingleTapJob = CoroutineScope(Dispatchers.Main.immediate).launch {
                        delay(DOUBLE_TAP_WINDOW_MS)
                        if (lastDateTapElapsed != 0L) {
                            lastDateTapElapsed = 0L
                            openDefaultCalendarApp(app)
                        }
                    }
                }
            }

            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
                -> {
                isShowingCalendarEvent = false
                background(context) {
                    val manager = AppWidgetManager.getInstance(context)
                    manager.getAppWidgetIds(component(context)).forEach {
                        DigitalClockWidgetRenderer.update(context, manager, it)
                    }
                }
            }
        }
    }

    private fun queryNextCalendarEvent(context: Context): String {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return "Allow Calendar"

        val now = System.currentTimeMillis()
        val endRange = now + 7L * 24L * 60L * 60L * 1000L
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now)
        ContentUris.appendId(builder, endRange)

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
        )

        return runCatching {
            context.contentResolver.query(
                builder.build(),
                projection,
                "${CalendarContract.Instances.BEGIN} >= ?",
                arrayOf(now.toString()),
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val title = cursor.getString(0)?.trim().takeUnless { it.isNullOrEmpty() } ?: "Event"
                    val begin = cursor.getLong(1)
                    val allDay = cursor.getInt(2) == 1
                    val whenLabel = if (allDay) {
                        SimpleDateFormat("EEE", Locale.getDefault()).format(Date(begin))
                    } else {
                        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(begin))
                    }
                    "$whenLabel • $title"
                } else {
                    "Nothing Planned"
                }
            } ?: "Nothing Planned"
        }.getOrDefault("Nothing Planned")
    }

    private fun openDefaultWeatherApp(context: Context) {
        val pm = context.packageManager
        val knownWeatherPackages = listOf(
            "com.coloros.weather2",
            "com.oplus.weather",
            "com.oneplus.weather",
            "net.oneplus.weather",
            "com.coloros.weather",
            "com.google.android.apps.weather",
        )
        for (pkg in knownWeatherPackages) {
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(launch) }.isSuccess) return
            }
        }

        val categoryIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_WEATHER)
        }
        val resolvedWeather = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(categoryIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(categoryIntent, 0)
        }
        for (info in resolvedWeather) {
            val pkg = info.activityInfo?.packageName ?: continue
            val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(launch) }.isSuccess) return
        }

        val webWeather = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=weather")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(webWeather) }
    }

    private fun openDefaultCalendarApp(context: Context) {
        val calendarIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_CALENDAR)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(calendarIntent) }
    }

    private fun background(context: Context, work: suspend (ClockStore) -> Unit) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                work(ClockStore(app))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_WEATHER_TAP = "app.expressiveclock.action.WEATHER_TAP"
        const val ACTION_DATE_TAP = "app.expressiveclock.action.DATE_TAP"
        private const val DOUBLE_TAP_WINDOW_MS = 420L

        @Volatile
        private var lastWeatherTapElapsed: Long = 0L

        @Volatile
        private var lastDateTapElapsed: Long = 0L

        @Volatile
        private var isShowingCalendarEvent: Boolean = false

        @Volatile
        private var pendingWeatherSingleTapJob: Job? = null

        @Volatile
        private var pendingDateSingleTapJob: Job? = null

        @Volatile
        private var dateOverlayResetJob: Job? = null

        fun component(context: Context) = ComponentName(context, DigitalClockWidgetProvider::class.java)

        fun refreshAll(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(component(app))
            if (ids.isEmpty()) return
            CoroutineScope(Dispatchers.Default).launch {
                ids.forEach { DigitalClockWidgetRenderer.update(app, manager, it) }
            }
        }

        fun placedIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(component(context))
    }
}