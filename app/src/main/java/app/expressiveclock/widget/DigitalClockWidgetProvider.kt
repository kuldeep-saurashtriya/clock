package app.expressiveclock.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import app.expressiveclock.data.ClockStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
                val isDoubleTap = (now - lastWeatherTapElapsed) <= 550L
                lastWeatherTapElapsed = now

                if (isDoubleTap) {
                    lastWeatherTapElapsed = 0L
                    openDefaultWeatherApp(context)
                } else {
                    background(context) {
                        val manager = AppWidgetManager.getInstance(context)
                        val ids = manager.getAppWidgetIds(component(context))
                        ids.forEach {
                            DigitalClockWidgetRenderer.update(context, manager, it, weatherOverride = "⏳ ...")
                        }
                        ids.forEach {
                            DigitalClockWidgetRenderer.update(context, manager, it, forceWeatherRefresh = true)
                        }
                    }
                }
            }
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
                -> background(context) {
                val manager = AppWidgetManager.getInstance(context)
                manager.getAppWidgetIds(component(context)).forEach {
                    DigitalClockWidgetRenderer.update(context, manager, it)
                }
            }
        }
    }

    private fun openDefaultWeatherApp(context: Context) {
        val pm = context.packageManager

        // 1. Try known OxygenOS / ColorOS / OnePlus Weather package names
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

        // 2. Resolve any installed app declaring CATEGORY_APP_WEATHER
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

        // 3. Fallback: scan installed launchable apps with "weather" in package name
        val mainLauncher = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val allApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainLauncher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainLauncher, 0)
        }
        for (info in allApps) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg.contains("weather", ignoreCase = true) && pkg != context.packageName) {
                val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(launch) }.isSuccess) return
            }
        }

        // 4. Last resort if no weather app exists on device
        val webWeather = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=weather")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(webWeather) }
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

        @Volatile
        private var lastWeatherTapElapsed: Long = 0L

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