package io.github.bucho2398.elsalto

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

private fun rad(d: Double) = d * PI / 180
private fun mod(a: Double, b: Double) = ((a % b) + b) % b

const val SITE_LAT = 25.37819
const val SITE_LON = -100.29952
val DIRS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/**
 * Solar azimuth (degrees from north, clockwise) and apparent elevation. A port of mourner/suncalc's
 * getPosition: Meeus ch. 25 apparent coordinates on Terrestrial Time (ΔT from the Espenak & Meeus
 * fits), sidereal time on UT, Meeus 16.4 refraction. Cached per second.
 * suncalc © Volodymyr Agafonkin, BSD 2-Clause: see LICENSE-suncalc.
 */
object Sun {
    private val cache = HashMap<Long, DoubleArray>()

    fun at(ts: Long): DoubleArray = synchronized(cache) {
        cache.getOrPut(ts) { compute(ts.toDouble(), SITE_LAT, SITE_LON) }.also { if (cache.size > 200_000) cache.clear() }
    }

    private fun deltaT(d: Double): Double {
        val y = 2000 + d / 365.2425
        if (y < 2050) { val t = y - 2000; return 62.92 + t * (0.32217 + t * 0.005589) }
        val t = (y - 1820) / 100
        return -20 + 32 * t * t - 0.5628 * (2150 - y)
    }

    fun compute(ts: Double, lat: Double, lon: Double): DoubleArray {
        val r = PI / 180
        val d = ts / 86400 - 0.5 + 2440588 - 2451545
        val t = (d + deltaT(d) / 86400) / 36525
        val l0 = r * (280.46646 + t * (36000.76983 + t * 0.0003032))
        val m = r * (357.52911 + t * (35999.05029 - t * 0.0001537))
        val sm = sin(m); val cm = cos(m)
        val c = r * ((1.914602 - t * (0.004817 + t * 0.000014)) * sm + (0.019993 - 0.000101 * t) * 2 * sm * cm + 0.000289 * sm * (3 - 4 * sm * sm))
        val om = r * (125.04 - 1934.136 * t)
        val l = l0 + c - r * (0.00569 + 0.00478 * sin(om))
        val e = r * (23.439291 - t * (0.0130042 + t * (0.00000016 - t * 0.000000504))) + r * 0.00256 * cos(om)
        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        val h = r * (280.46061837 + 360.98564736629 * d) + r * lon - ra
        val phi = r * lat
        val alt = asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h))
        val az = mod(atan2(sin(h), cos(h) * sin(phi) - tan(dec) * cos(phi)) / r + 540, 360.0)
        val hr = max(alt, 0.0)
        return doubleArrayOf(az, (alt + 0.0002967 / tan(hr + 0.00312536 / (hr + 0.08901179))) / r)
    }
}

fun inWindow(hour: Double, windows: List<IntArray>): Boolean = windows.any { (a, b) ->
    (a <= b && a <= hour && hour < b) || (a > b && (hour >= a || hour < b))
}

/** A wall's face: the bearing it faces, its tilt, its terrain horizon and your shade windows. */
class Face(val deg: Double?, private val hz: DoubleArray, private val step: Double, val shade: List<IntArray>, val tilt: Double) {
    /** Share of the sky the face sees: half for a vertical face, more for a slab, less for an overhang; less again for terrain. */
    val sky: Double

    init {
        val own = (1 + cos(rad(tilt))) / 2
        sky = if (deg != null && hz.isNotEmpty()) {
            own * (-80..80 step 10).map { cos(rad(hzAt(deg + it))).pow(2) }.average()
        } else own * 0.8
    }

    fun hzAt(az: Double): Double {
        if (hz.isEmpty()) return 0.0
        val i = mod(az, 360.0) / step
        val a = floor(i).toInt() % hz.size
        val b = (a + 1) % hz.size
        return hz[a] + (hz[b] - hz[a]) * (i - floor(i))
    }

    /** "sun" with the cosine of incidence, or why not: night, unknown, behind, terrain, shade. */
    fun state(ts: Long, localHour: Double): Pair<String, Double> {
        val (az, el) = Sun.at(ts).let { it[0] to it[1] }
        if (el <= 0) return "night" to 0.0
        val d = deg ?: return "unknown" to 0.0
        val b = rad(tilt)
        val inc = sin(rad(el)) * cos(b) + cos(rad(el)) * sin(b) * cos(rad(az - d))
        if (inc <= 0.03) return "behind" to 0.0
        if (el < hzAt(az)) return "terrain" to 0.0
        if (inWindow(localHour, shade)) return "shade" to 0.0
        return "sun" to inc
    }

    /**
     * Sun over the hour ending at [tsEnd], the period Open-Meteo averages its radiation over: the mean
     * cosine of incidence while sunlight reaches the face, and the mean sine of the sun's elevation.
     */
    fun hourLight(tsEnd: Long, offset: Int): DoubleArray {
        var inc = 0.0; var sinEl = 0.0
        for (k in 0 until 4) {
            val ts = tsEnd - 3600 + k * 900 + 450
            inc += state(ts, mod((ts + offset).toDouble(), 86400.0) / 3600).second / 4
            sinEl += max(0.0, sin(rad(Sun.at(ts)[1]))) / 4
        }
        return doubleArrayOf(inc, sinEl)
    }

    /** Local-hour spans of a day (starting at dayStart, Unix seconds) when the face's state is [want]. */
    fun spans(dayStart: Long, offset: Int, want: String = "sun", stepMin: Int = 10): List<DoubleArray> {
        val out = mutableListOf<DoubleArray>()
        var cur: Int? = null
        for (m in 0..24 * 60 step stepMin) {
            val on = m < 24 * 60 && state(dayStart + m * 60L, m / 60.0).first == want
            if (on && cur == null) cur = m
            if (!on && cur != null) {
                out += doubleArrayOf(cur / 60.0, m / 60.0); cur = null
            }
        }
        return out
    }
}

fun hoursOf(spans: List<DoubleArray>) = (spans.sumOf { it[1] - it[0] } * 10).roundToInt() / 10.0
fun hm(h: Double): String {
    val m = (h * 60).roundToInt()
    return if (m >= 1440) "24:00" else "%02d:%02d".format(m / 60 % 24, m % 60)
}

/** How a wall takes rain, wind and sky, from its tags and features. */
class Character(
    val tilt: Double, val kind: String, val cap: Double, val rain: Double, val wind: Double,
    val dries: Double, val seeps: Boolean, val sunTag: String?, val tags: List<String>,
)

val SUN_TAGS = linkedMapOf("Morning sun" to 90.0, "Noon sun" to 180.0, "All day sun" to 180.0, "Afternoon sun" to 270.0, "Always shady" to 0.0)
private val SHOWN_TAGS = setOf(
    "Dry in the rain", "Wet in rain", "Dries quickly", "Sheltered from wind", "Windy", "Overhung", "Roof", "Vertical", "Tufa",
    "Always shady", "Morning sun", "Afternoon sun", "Noon sun", "All day sun", "Brittle when wet",
)

fun character(w: Wall): Character {
    val f = w.features
    val tags = w.tags
    // Tilt from horizontal and the water (mm) the face holds when soaked.
    val (tilt, kind, cap) = when {
        f["roof"] == true || f["cave"] == true -> Triple(125.0, "roof or cave", 0.8)
        f["overhang"] == true -> Triple(105.0, "overhanging", 1.0)
        f["slab"] == true && f["vertical"] != true -> Triple(72.0, "slab", 3.0)
        else -> Triple(90.0, "vertical", 1.5)
    }
    var rain = when {
        "Dry in the rain" in tags -> 0.1
        kind == "roof or cave" -> 0.25
        kind == "overhanging" -> 0.5
        else -> 1.0
    }
    if ("Wet in rain" in tags) rain *= 1.5
    var wind = if ("Sheltered from wind" in tags) 0.5 else if ("Windy" in tags) 1.4 else 1.0
    if (kind == "roof or cave") wind *= 0.7
    val dries = if ("Dries quickly" in tags) 1.4 else 1.0
    val seeps = f["tufa"] == true || f["seep"] == true || "Tufa" in tags || "Wet in rain" in tags
    val sunTag = SUN_TAGS.keys.firstOrNull { it in tags }
    return Character(tilt, kind, cap, rain, wind, dries, seeps, sunTag, tags.filter { it in SHOWN_TAGS }.sorted())
}

/** Saturation vapour pressure (kPa), FAO-56's Magnus form as Open-Meteo uses it. */
fun esat(t: Double) = 0.6108 * exp(17.27 * t / (t + 237.3))

/** Psychrometric constant (kPa/°C) at a height, from FAO-56's air pressure (eq. 7, 8). */
fun psychro(elev: Double) = 0.000665 * 101.3 * ((293 - 0.0065 * elev) / 293).pow(5.26)

private const val SIGMA = 5.670e-8
private const val ROCK_ALBEDO = 0.23
private const val GROUND_ALBEDO = 0.2
private val WIND_2M = 4.87 / ln(67.8 * 10 - 5.42) / (4.87 / ln(67.8 * 2 - 5.42))

/**
 * Water evaporating from (or, negative, condensing on) a wet rock face, mm per hour: Penman's
 * equation for a wet surface with FAO-56's terms as in Open-Meteo's hourly ET₀ (Δ, γ at the wall's
 * height, net longwave to the sky the face sees, 10 %/50 % of net radiation into the rock by day and
 * night), and a facade convection coefficient h = 5.7 + 3.8 u for the air side.
 */
fun evaporation(t: Double, td: Double, rh: Double, windFace: Double, absorbedSw: Double, sky: Double, sinEl: Double, ghi: Double, doy: Int, elev: Double): Double {
    val es = esat(t); val ea = esat(td)
    val delta = 4098 * es / (t + 237.3).pow(2)
    val ra = 1361 * (1 + 0.033 * cos(2 * PI * doy / 365)) * sinEl
    val rso = (0.75 + 0.00002 * elev) * ra
    val rel = if (sinEl > 0.05) min(1.0, max(0.3, ghi / rso)) else 0.8 - min(max(rh, 0.0), 100.0) / 100 * 0.4
    val lw = SIGMA * (t + 273.16).pow(4) * (0.34 - 0.14 * sqrt(ea)) * (1.35 * rel - 0.35) * sky
    val rn = absorbedSw - lw
    val g = if (absorbedSw > 0) 0.1 * rn else 0.5 * rn
    val h = 5.7 + 3.8 * windFace
    val le = (delta * (rn - g) + h * (es - ea)) / (delta + psychro(elev))
    return le * 3600 / 2.45e6
}

class Budget(var rain: Double = 0.0, var face: Double = 0.0, var runoff: Double = 0.0, var cond: Double = 0.0, var drain: Double = 0.0, var evap: Double = 0.0, var seep: Double = 0.0)

class MonthSun(val hours: Double, val spans: List<DoubleArray>, val terrainHours: Double, val terrain: List<DoubleArray>)

class WallState(
    val wall: Wall,
    val aspect: String?, val deg: Double?, val aspectSource: String?,
    val terrain: Terrain?, val horizon: DoubleArray, val hzStep: Double, val sky: Int,
    val shade: List<IntArray>, val fav: Boolean,
    val wetPct: Int, val state: String, val cap: Double, val seeping: Boolean, val dryAt: Long?,
    val kind: String, val crTags: List<String>, val catchmentHa: Double, val windExpo: Int,
    val tempHere: Double, val rhHere: Double, val budget: Budget,
    val now: String, val sunToday: List<DoubleArray>, val sunHours: Double, val terrainToday: List<DoubleArray>,
    val year: List<MonthSun>,
) {
    val sunText get() = sunToday.joinToString(", ") { "${hm(it[0])}–${hm(it[1])}" }
    val terrainText get() = terrainToday.joinToString(", ") { "${hm(it[0])}–${hm(it[1])}" }
    val shadeText get() = shade.joinToString(", ") { "%02d:00–%02d:00".format(it[0], it[1]) }
}

class HourPoint(val t: Long, val rain: Double, val prob: Double)

class Conditions(
    val updated: Long,
    val offset: Int,
    val site: SiteWeather,
    val rainPast: DoubleArray,   // 24, 48, 72 h
    val rainNext: DoubleArray,
    val lastRain: Long?,
    val nextRain: Long?,
    val sunrise: Long,
    val sunset: Long,
    val hours: List<HourPoint>,
    val nowIndex: Int,
    val paths: Map<String, List<DoubleArray>>,
    val sunNow: DoubleArray,
    val walls: List<WallState>,
    /** The wall data this was computed from: its version, date and attribution. */
    val crag: CragData = CragData.EMPTY,
) {
    val current get() = site.current
    val fog get() = current.optInt("weather_code") in listOf(45, 48)
}

object Model {
    private fun r1(x: Double) = (x * 10).roundToInt() / 10.0

    fun pointKey(w: Wall, t: Terrain?, medianElev: Double): Triple<Double, Double, Double>? {
        if (w.lat == null || w.lon == null) return null
        val elev = t?.elev ?: medianElev
        // Half-to-even, like the data tools (Python's round), so both group walls the same way.
        return Triple(Math.rint(w.lat * 100) / 100.0, Math.rint(w.lon * 100) / 100.0, Math.rint(elev / 50) * 50.0)
    }

    fun compute(crag: CragData, prefs: WallPrefs, site: SiteWeather, local: Map<Triple<Double, Double, Double>, Hourly>, now: Long): Conditions {
        val h = site.hourly
        val n = h.size
        val offset = site.offset
        val idxNow = (0 until n).lastOrNull { h.time[it] <= now } ?: 0
        val P = h["precipitation"].map { if (it.isNaN()) 0.0 else it }
        fun total(a: Int, b: Int) = r1((max(0, a) until max(0, min(n, b))).sumOf { P[it] })
        val prob = h["precipitation_probability"]
        val lastRain = (idxNow downTo 0).firstOrNull { P[it] >= 0.2 }?.let { h.time[it] }
        val nextRain = (idxNow + 1 until n).firstOrNull { P[it] >= 0.2 || (!prob[it].isNaN() && prob[it] >= 50) }?.let { h.time[it] }
        val dayStart = Math.floorDiv(now + offset, 86400L) * 86400 - offset

        val year = java.time.Instant.ofEpochSecond(now).atOffset(java.time.ZoneOffset.UTC).year
        val months = (1..12).map { java.time.LocalDate.of(year, it, 15).atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC) - offset }
        val elevs = crag.terrain.values.map { it.elev }.sorted()
        val medianElev = if (elevs.isEmpty()) 1500.0 else elevs[elevs.size / 2]

        val walls = crag.walls.map { w ->
            val t = crag.terrain[w.id]
            val ch = character(w)
            val tagDeg = ch.sunTag?.let { SUN_TAGS[it] }
            fun agree(a: Double, b: Double) = abs(mod(a - b + 180, 360.0) - 180) <= 67.5
            val pa = prefs.aspect(w.id)
            var aspect: String?; var dg: Double?; var source: String?
            when {
                pa in DIRS -> { aspect = pa; dg = DIRS.indexOf(pa) * 45.0; source = "yours" }
                w.deg != null -> { aspect = w.aspect; dg = w.deg; source = "theCrag" }
                t != null && t.reliable && (tagDeg == null || agree(t.facing, tagDeg)) -> {
                    dg = t.facing; aspect = DIRS[(t.facing / 45).roundToInt() % 8]; source = "terrain"
                }
                tagDeg != null -> { dg = tagDeg; aspect = DIRS[(tagDeg / 45).roundToInt() % 8]; source = "sun tag" }
                else -> { aspect = null; dg = null; source = null }
            }
            val shade = prefs.shade(w.id)
            val face = Face(dg, t?.horizon ?: DoubleArray(0), crag.terrainStep, shade, ch.tilt)
            val wh = pointKey(w, t, medianElev)?.let { local[it] }?.takeIf { it.size == n && it.time[0] == h.time[0] } ?: h
            val catchHa = (t?.catchment ?: 0.0) / 1e4

            val temp = wh["temperature_2m"]; val rhA = wh["relative_humidity_2m"]; val tdA = wh["dew_point_2m"]
            val pr = wh["precipitation"]; val ws = wh["wind_speed_10m"]; val wd = wh["wind_direction_10m"]
            val vis = wh["visibility"]; val code = wh["weather_code"]
            val dni = wh["direct_normal_irradiance"]; val dif = wh["diffuse_radiation"]
            fun v(a: DoubleArray, i: Int, d: Double) = a[i].takeIf { !it.isNaN() } ?: d
            // Open-Meteo's rain and radiation cover the hour before each step, its temperature, humidity
            // and wind are values at the step: average the two ends to match the hour.
            fun mid(a: DoubleArray, i: Int, d: Double): Double {
                val b = a[i]; val p = a[max(i - 1, 0)].takeIf { !it.isNaN() } ?: b
                return if (b.isNaN()) d else (p + b) / 2
            }
            val elev = t?.elev ?: medianElev

            var water = 0.0; var seepStore = 0.0
            var dryAt: Long? = null; var wetNow = 0.0; var seepNow = 0.0
            val tot = Budget()
            val recent = ArrayDeque<Double>()
            for (i in max(0, idxNow - 71) until n) {
                val ts = wh.time[i]
                val r = v(pr, i, 0.0)
                val tc = mid(temp, i, 15.0); val rh = mid(rhA, i, 70.0); val td = mid(tdA, i, tc - 5)
                val wind = mid(ws, i, 0.0) / 3.6
                val wdir = v(wd, i, 0.0)
                recent.addLast(r); if (recent.size > 6) recent.removeFirst()
                // Wind at the wall: less when terrain rises upwind; the wind tags.
                val expo = max(0.15, min(1.0, 1 - face.hzAt(wdir) / 45)) * ch.wind
                // Rain onto the face: from above (slabs), wind-driven (ISO 15927-3), runoff from the slope above.
                var onface = 0.0
                if (r > 0) {
                    val vertical = r * max(0.0, cos(rad(ch.tilt)))
                    val c = if (dg != null) cos(rad(wdir - dg)) else 1 / PI
                    val driven = 2.0 / 9 * wind * expo * r.pow(8.0 / 9) * max(0.0, c)
                    var runoff = if (recent.sum() > 3) r * 0.25 * min(1.0, sqrt(catchHa / 2)) else 0.0
                    if (ch.kind != "vertical" && ch.kind != "slab") runoff *= 0.3
                    onface = (vertical + driven + runoff) * ch.rain
                    if (i <= idxNow) tot.runoff += runoff * ch.rain
                    seepStore += r * min(3.0, catchHa) * 0.5
                }
                if (i <= idxNow) { tot.rain += r; tot.face += onface }
                val outflow = seepStore * 0.015
                seepStore -= outflow
                val seepage = outflow * (if (ch.seeps) 0.05 else 0.01)
                // Sunlight on the face over the hour (Open-Meteo's tilted irradiance): direct sun at its
                // incidence while it reaches the face, diffuse sky less the terrain, light off the ground.
                val dn = v(dni, i, 0.0); val df = v(dif, i, 0.0)
                val (inc, sinEl) = face.hourLight(ts, offset).let { it[0] to it[1] }
                val ghi = dn * sinEl + df
                val sw = dn * inc + df * face.sky + GROUND_ALBEDO * ghi * (1 - face.sky)
                val doy = java.time.Instant.ofEpochSecond(ts).atOffset(java.time.ZoneOffset.UTC).dayOfYear
                val e = evaporation(tc, td, rh, wind * WIND_2M * expo, (1 - ROCK_ALBEDO) * sw, face.sky, sinEl, ghi, doy, elev)
                // Condensation: dew when Penman turns negative, fog droplets caught by exposed faces.
                val c0 = v(code, i, -1.0).toInt()
                val fog = c0 == 45 || c0 == 48 || (!vis[i].isNaN() && vis[i] < 1000) || rh >= 98
                val cond = max(0.0, -e) + if (fog && sw < 100) 0.04 * (0.4 + 0.6 * min(1.0, expo)) else 0.0
                val evap = max(0.0, e) * ch.dries
                val before = water
                val spilled = max(0.0, water + onface + seepage + cond - ch.cap)
                water = min(ch.cap, water + onface + seepage + cond)
                // The rock keeps a film (35 % of what it holds soaked); water above it runs down the face,
                // most of it within the hour on steep rock, more slowly off a slab.
                val film = 0.35 * ch.cap
                val drained = max(0.0, water - film) * (if (ch.kind == "slab") 0.3 else 0.6)
                water -= drained
                val dried = min(water, evap)
                water -= dried
                if (cond > 0) water = min(water, max(before, film))
                if (i <= idxNow) {
                    tot.cond += cond; tot.seep += seepage
                    tot.drain += drained + spilled; tot.evap += dried
                }
                if (i == idxNow) { wetNow = water; seepNow = seepStore }
                // The first time it's dry from now, even if rain or dew wets it again later.
                if (i > idxNow && dryAt == null && water < 0.08 * ch.cap) dryAt = ts
            }
            val frac = wetNow / ch.cap
            val state = if (frac < 0.08) "dry" else if (frac < 0.5) "damp" else "wet"
            val spans = if (dg != null) face.spans(dayStart, offset, "sun") else emptyList()
            val blocked = if (dg != null) face.spans(dayStart, offset, "terrain") else emptyList()
            val stNow = face.state(now, mod((now + offset).toDouble(), 86400.0) / 3600).first
            val cal = months.map { ms ->
                val sp = if (dg != null) face.spans(ms, offset, "sun", 15) else emptyList()
                val tb = if (dg != null) face.spans(ms, offset, "terrain", 15) else emptyList()
                MonthSun(hoursOf(sp), sp, hoursOf(tb), tb)
            }
            val wdNow = v(wd, idxNow, 0.0)
            val expoNow = max(0.15, min(1.0, 1 - face.hzAt(wdNow) / 45)) * ch.wind
            listOf(tot::rain, tot::face, tot::runoff, tot::cond, tot::drain, tot::evap, tot::seep).forEach { it.set(r1(it.get())) }
            WallState(
                wall = w, aspect = aspect, deg = dg, aspectSource = source, terrain = t,
                horizon = t?.horizon ?: DoubleArray(0), hzStep = crag.terrainStep, sky = (face.sky * 100).roundToInt(),
                shade = shade, fav = prefs.fav(w.id),
                wetPct = min(100, (100 * frac).roundToInt()), state = state, cap = ch.cap,
                seeping = seepNow > 10 && ch.seeps, dryAt = if (state != "dry") dryAt else null,
                kind = ch.kind, crTags = ch.tags, catchmentHa = (catchHa * 100).roundToInt() / 100.0,
                windExpo = (min(1.0, expoNow) * 100).roundToInt(),
                tempHere = v(temp, idxNow, Double.NaN), rhHere = v(rhA, idxNow, Double.NaN), budget = tot,
                now = stNow, sunToday = spans, sunHours = hoursOf(spans), terrainToday = blocked, year = cal,
            )
        }.sortedWith(compareBy<WallState>({ !it.fav }, { it.wall.zone }, { it.wall.name }))

        val hours = (max(0, idxNow - 72) until min(n, idxNow + 73)).map { HourPoint(h.time[it], P[it], prob[it].takeIf { p -> !p.isNaN() } ?: 0.0) }
        val today = site.days.firstOrNull { it.t <= now && now < it.t + 86400 } ?: site.days.first()

        fun path(day0: Long) = (0 until 24 * 60 step 20).mapNotNull { m ->
            Sun.at(day0 + m * 60L).takeIf { it[1] > -1 }?.copyOf()
        }
        val jun = java.time.LocalDate.of(year, 6, 21).atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC) - offset
        val dec = java.time.LocalDate.of(year, 12, 21).atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC) - offset

        return Conditions(
            updated = now, offset = offset, site = site,
            rainPast = doubleArrayOf(total(idxNow - 23, idxNow + 1), total(idxNow - 47, idxNow + 1), total(idxNow - 71, idxNow + 1)),
            rainNext = doubleArrayOf(total(idxNow + 1, idxNow + 25), total(idxNow + 1, idxNow + 49), total(idxNow + 1, idxNow + 73)),
            lastRain = lastRain, nextRain = nextRain, sunrise = today.sunrise, sunset = today.sunset,
            hours = hours, nowIndex = idxNow - max(0, idxNow - 72),
            paths = mapOf("today" to path(dayStart), "jun" to path(jun), "dec" to path(dec)),
            sunNow = Sun.compute(now.toDouble(), SITE_LAT, SITE_LON),
            walls = walls,
            crag = crag,
        )
    }
}
