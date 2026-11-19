package app.expressiveclock.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

object WidgetWeatherHelper {

    private const val PREFS_NAME = "widget_weather_cache"
    private const val KEY_TEXT = "weather_text"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes

    suspend fun getWeatherBadge(context: Context, forceRefresh: Boolean = false): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedText = prefs.getString(KEY_TEXT, null)
        val lastUpdated = prefs.getLong(KEY_UPDATED_AT, 0L)
        val now = System.currentTimeMillis()

        if (!forceRefresh && !cachedText.isNullOrBlank() && (now - lastUpdated) < CACHE_TTL_MS) {
            return cachedText
        }

        val fresh = fetchFromOpenMeteo(context)
        if (!fresh.isNullOrBlank()) {
            prefs.edit()
                .putString(KEY_TEXT, fresh)
                .putLong(KEY_UPDATED_AT, now)
                .apply()
            return fresh
        }

        return cachedText.orEmpty()
    }

    private suspend fun fetchFromOpenMeteo(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            val (lat, lon) = resolveCoordinates(context)
            val urlStr =
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }

            if (conn.responseCode != 200) return@runCatching null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val current = JSONObject(body).getJSONObject("current")
            val temp = current.getDouble("temperature_2m").roundToInt()
            val code = current.getInt("weather_code")
            val isDay = current.optInt("is_day", 1) == 1

            val icon = weatherCodeToEmoji(code, isDay)
            "$icon$temp°"
        }.getOrNull()
    }

    private fun resolveCoordinates(context: Context): Pair<Double, Double> {
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCoarse) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            for (provider in providers) {
                @Suppress("MissingPermission")
                val loc = runCatching { lm?.getLastKnownLocation(provider) }.getOrNull()
                if (loc != null) {
                    return loc.latitude to loc.longitude
                }
            }
        }
        return 12.9716 to 77.5946
    }

    private fun weatherCodeToEmoji(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1, 2 -> if (isDay) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "❄️"
        95, 96, 99 -> "⛈️"
        else -> "🌤️"
    }
}