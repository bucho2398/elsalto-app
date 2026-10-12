package io.github.bucho2398.elsalto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Net {
    fun bytes(url: String, timeoutMs: Int = 20000): ByteArray? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "ElSaltoConditions/1.0 (Android)")
        try {
            if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    fun text(url: String, timeoutMs: Int = 20000): String? = bytes(url, timeoutMs)?.toString(Charsets.UTF_8)
}

/** Hourly series, with NaN for missing values. */
class Hourly(val time: LongArray, private val series: Map<String, DoubleArray>) {
    val size get() = time.size
    operator fun get(name: String): DoubleArray = series[name] ?: DoubleArray(time.size) { Double.NaN }

    companion object {
        fun parse(o: JSONObject): Hourly {
            val t = o.getJSONArray("time")
            val time = LongArray(t.length()) { t.getLong(it) }
            val series = mutableMapOf<String, DoubleArray>()
            for (k in o.keys()) {
                if (k == "time") continue
                val a = o.optJSONArray(k) ?: continue
                series[k] = DoubleArray(a.length()) { if (a.isNull(it)) Double.NaN else a.optDouble(it) }
            }
            return Hourly(time, series)
        }
    }
}

class Day(
    val t: Long, val sunrise: Long, val sunset: Long, val rain: Double,
    val max: Double, val min: Double, val code: Int, val prob: Double,
)

class SiteWeather(
    val offset: Int,
    val elevation: Double,
    val current: JSONObject,
    val hourly: Hourly,
    val days: List<Day>,
)

/** Open-Meteo: the area's weather (the tab and the tile) and every wall's own spot. Cached 15 minutes. */
object WeatherStore {
    private const val TTL = 15 * 60 * 1000L
    private const val HOURLY = "temperature_2m,relative_humidity_2m,dew_point_2m,precipitation,precipitation_probability," +
        "wind_speed_10m,wind_gusts_10m,wind_direction_10m,shortwave_radiation,direct_normal_irradiance,diffuse_radiation,cloud_cover,weather_code"
    private const val WALL_HOURLY = "temperature_2m,relative_humidity_2m,dew_point_2m,precipitation,wind_speed_10m," +
        "wind_direction_10m,direct_normal_irradiance,diffuse_radiation,cloud_cover,is_day,visibility,weather_code"

    private fun cached(ctx: Context, name: String, url: String, force: Boolean, timeoutMs: Int = 20000): String? {
        val f = File(ctx.filesDir, name)
        val old = if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrNull() else null
        val fresh = old != null && old.optString("url") == url && System.currentTimeMillis() - old.optLong("at") < TTL
        if (fresh && !force) return old!!.getString("body")
        val body = Net.text(url, timeoutMs) ?: return old?.takeIf { it.optString("url") == url }?.getString("body")
        f.writeText(JSONObject().put("url", url).put("at", System.currentTimeMillis()).put("body", body).toString())
        return body
    }

    fun site(ctx: Context, lat: Double, lon: Double, elev: Double?, force: Boolean): SiteWeather? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(java.util.Locale.US, lat, lon) +
            (elev?.let { "&elevation=%.0f".format(java.util.Locale.US, it) } ?: "") +
            "&hourly=$HOURLY" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,dew_point_2m,precipitation,wind_speed_10m,wind_gusts_10m,wind_direction_10m,weather_code,is_day,cloud_cover" +
            "&daily=sunrise,sunset,precipitation_sum,temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max" +
            "&past_days=3&forecast_days=4&timezone=auto&timeformat=unixtime"
        val body = cached(ctx, "weather-site.json", url, force) ?: return null
        return parseSite(body)
    }

    fun parseSite(body: String): SiteWeather? = runCatching {
        val o = JSONObject(body)
        val d = o.getJSONObject("daily")
        val n = d.getJSONArray("time").length()
        fun arr(k: String) = d.getJSONArray(k)
        val days = (0 until n).map {
            Day(
                arr("time").getLong(it), arr("sunrise").getLong(it), arr("sunset").getLong(it),
                arr("precipitation_sum").optDouble(it, 0.0), arr("temperature_2m_max").optDouble(it),
                arr("temperature_2m_min").optDouble(it), arr("weather_code").optInt(it),
                arr("precipitation_probability_max").optDouble(it, 0.0),
            )
        }
        SiteWeather(o.optInt("utc_offset_seconds", -21600), o.optDouble("elevation"), o.getJSONObject("current"),
            Hourly.parse(o.getJSONObject("hourly")), days)
    }.getOrNull()

    /** Hourly weather at each point (lat, lon, elevation), in one request. */
    fun points(ctx: Context, points: List<Triple<Double, Double, Double>>, force: Boolean): List<Hourly>? {
        if (points.isEmpty()) return emptyList()
        val loc = java.util.Locale.US
        val url = "https://api.open-meteo.com/v1/forecast?latitude=" + points.joinToString(",") { "%.3f".format(loc, it.first) } +
            "&longitude=" + points.joinToString(",") { "%.3f".format(loc, it.second) } +
            "&elevation=" + points.joinToString(",") { "%.0f".format(loc, it.third) } +
            "&hourly=$WALL_HOURLY&past_days=3&forecast_days=4&timezone=auto&timeformat=unixtime"
        val body = cached(ctx, "weather-walls.json", url, force, 40000) ?: return null
        return parsePoints(body)
    }

    fun parsePoints(body: String): List<Hourly>? = runCatching {
        val t = body.trim()
        val list = if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t))
        (0 until list.length()).map { Hourly.parse(list.getJSONObject(it).getJSONObject("hourly")) }
    }.getOrNull()
}
