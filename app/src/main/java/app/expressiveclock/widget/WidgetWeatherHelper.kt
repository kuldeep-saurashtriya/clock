package app.expressiveclock.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.math.roundToInt

object WidgetWeatherHelper {

    enum class WeatherIconKind {
        LOADING,
        CLEAR_DAY,
        CLEAR_NIGHT,
        PARTLY_CLOUDY_DAY,
        PARTLY_CLOUDY_NIGHT,
        CLOUDY,
        FOG,
        RAIN,
        SNOW,
        STORM,
    }

    data class ParsedBadge(
        val iconKind: WeatherIconKind?,
        val isDay: Boolean,
        val label: String,
    )

    private const val PREFS_NAME = "widget_weather_cache"
    private const val KEY_TEMP = "weather_temp"
    private const val KEY_CODE = "weather_code"
    private const val KEY_IS_DAY_FALLBACK = "weather_is_day"
    private const val KEY_SUNRISE_MS = "weather_sunrise_ms"
    private const val KEY_SUNSET_MS = "weather_sunset_ms"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes

    suspend fun getWeatherBadge(context: Context, forceRefresh: Boolean = false): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUpdated = prefs.getLong(KEY_UPDATED_AT, 0L)
        val now = System.currentTimeMillis()
        val hasCached = prefs.contains(KEY_TEMP) && prefs.contains(KEY_CODE)

        if (!forceRefresh && hasCached && (now - lastUpdated) < CACHE_TTL_MS) {
            return formatCached(prefs, now)
        }

        val fresh = fetchFromOpenMeteo(context, activeLocation = forceRefresh)
        if (fresh != null) {
            prefs.edit()
                .putInt(KEY_TEMP, fresh.temp)
                .putInt(KEY_CODE, fresh.code)
                .putBoolean(KEY_IS_DAY_FALLBACK, fresh.isDayFallback)
                .putLong(KEY_SUNRISE_MS, fresh.sunriseMs)
                .putLong(KEY_SUNSET_MS, fresh.sunsetMs)
                .putLong(KEY_UPDATED_AT, now)
                .apply()
            val isDay = resolveIsDay(now, fresh.sunriseMs, fresh.sunsetMs, fresh.isDayFallback)
            val kind = weatherCodeToKind(fresh.code, isDay)
            val dayFlag = if (isDay) "D" else "N"
            return "ICON:${kind.name}|$dayFlag|${fresh.temp}°"
        }

        return if (hasCached) formatCached(prefs, now) else ""
    }

    fun parseBadge(badge: String): ParsedBadge {
        if (!badge.startsWith("ICON:")) return ParsedBadge(null, true, badge)
        val parts = badge.removePrefix("ICON:").split('|')
        return when {
            parts.size >= 3 -> {
                val kind = runCatching { WeatherIconKind.valueOf(parts[0]) }.getOrNull()
                val isDay = parts[1].equals("D", ignoreCase = true)
                ParsedBadge(kind, isDay, parts[2])
            }
            parts.size == 2 -> {
                val kind = runCatching { WeatherIconKind.valueOf(parts[0]) }.getOrNull()
                val isDay = kind != WeatherIconKind.CLEAR_NIGHT && kind != WeatherIconKind.PARTLY_CLOUDY_NIGHT
                ParsedBadge(kind, isDay, parts[1])
            }
            else -> ParsedBadge(null, true, badge)
        }
    }

    private fun formatCached(prefs: android.content.SharedPreferences, nowMs: Long): String {
        val temp = prefs.getInt(KEY_TEMP, 0)
        val code = prefs.getInt(KEY_CODE, 0)
        val fallback = prefs.getBoolean(KEY_IS_DAY_FALLBACK, true)
        val sunriseMs = prefs.getLong(KEY_SUNRISE_MS, -1L)
        val sunsetMs = prefs.getLong(KEY_SUNSET_MS, -1L)
        val isDay = resolveIsDay(nowMs, sunriseMs, sunsetMs, fallback)
        val kind = weatherCodeToKind(code, isDay)
        val dayFlag = if (isDay) "D" else "N"
        return "ICON:${kind.name}|$dayFlag|$temp°"
    }

    private fun resolveIsDay(nowMs: Long, sunriseMs: Long, sunsetMs: Long, fallback: Boolean): Boolean {
        if (sunriseMs > 0L && sunsetMs > sunriseMs) {
            return nowMs in sunriseMs..sunsetMs
        }
        return fallback
    }

    private data class WeatherSnapshot(
        val temp: Int,
        val code: Int,
        val isDayFallback: Boolean,
        val sunriseMs: Long,
        val sunsetMs: Long,
    )

    private suspend fun fetchFromOpenMeteo(
        context: Context,
        activeLocation: Boolean,
    ): WeatherSnapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val (lat, lon) = resolveCoordinates(context, activeLocation)
            val urlStr =
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,weather_code,is_day" +
                        "&daily=sunrise,sunset&timezone=auto&forecast_days=1"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
                requestMethod = "GET"
            }

            if (conn.responseCode != 200) return@runCatching null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val root = JSONObject(body)
            val current = root.getJSONObject("current")
            val temp = current.getDouble("temperature_2m").roundToInt()
            val code = current.getInt("weather_code")
            val isDayFallback = current.optInt("is_day", 1) == 1

            val tzId = root.optString("timezone", TimeZone.getDefault().id)
            val daily = root.optJSONObject("daily")
            val sunriseIso = daily?.optJSONArray("sunrise")?.optString(0).orEmpty()
            val sunsetIso = daily?.optJSONArray("sunset")?.optString(0).orEmpty()

            val sunriseMs = parseLocalIsoToMillis(sunriseIso, tzId)
            val sunsetMs = parseLocalIsoToMillis(sunsetIso, tzId)

            WeatherSnapshot(temp, code, isDayFallback, sunriseMs, sunsetMs)
        }.getOrNull()
    }

    private fun parseLocalIsoToMillis(iso: String, tzId: String): Long {
        if (iso.isBlank()) return -1L
        return runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply {
                timeZone = TimeZone.getTimeZone(tzId)
            }
            fmt.parse(iso)?.time ?: -1L
        }.getOrDefault(-1L)
    }

    private suspend fun resolveCoordinates(
        context: Context,
        activeLocation: Boolean,
    ): Pair<Double, Double> {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) return 12.9716 to 77.5946

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return 12.9716 to 77.5946

        if (activeLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = if (hasFine) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            val fresh = withTimeoutOrNull(900L) {
                requestSingleLocation(context, lm, provider)
            }
            if (fresh != null) return fresh.latitude to fresh.longitude
        }

        val lastProviders = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        var best: Location? = null
        for (provider in lastProviders) {
            @Suppress("MissingPermission")
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || loc.time > best.time) best = loc
        }
        return if (best != null) best.latitude to best.longitude else (12.9716 to 77.5946)
    }

    @Suppress("MissingPermission")
    private suspend fun requestSingleLocation(
        context: Context,
        lm: LocationManager,
        provider: String,
    ): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (runCatching { !lm.isProviderEnabled(provider) }.getOrDefault(true)) return null
        return suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            runCatching {
                lm.getCurrentLocation(
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(context),
                ) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun weatherCodeToKind(code: Int, isDay: Boolean): WeatherIconKind = when (code) {
        0 -> if (isDay) WeatherIconKind.CLEAR_DAY else WeatherIconKind.CLEAR_NIGHT
        1, 2 -> if (isDay) WeatherIconKind.PARTLY_CLOUDY_DAY else WeatherIconKind.PARTLY_CLOUDY_NIGHT
        3 -> WeatherIconKind.CLOUDY
        45, 48 -> WeatherIconKind.FOG
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> WeatherIconKind.RAIN
        71, 73, 75, 77, 85, 86 -> WeatherIconKind.SNOW
        95, 96, 99 -> WeatherIconKind.STORM
        else -> if (isDay) WeatherIconKind.PARTLY_CLOUDY_DAY else WeatherIconKind.CLEAR_NIGHT
    }
}