package io.github.bucho2398.elsalto

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    /** A Unix time in El Salto's local time (the weather's UTC offset). */
    fun time(ts: Long, offset: Int, pattern: String): String =
        DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
            .format(Instant.ofEpochSecond(ts).atOffset(ZoneOffset.ofTotalSeconds(offset)))

    fun mm(x: Double): String = if (x == Math.floor(x)) x.toLong().toString() else "%.1f".format(Locale.US, x)

    fun num(x: Double, digits: Int = 1): String = if (x.isNaN()) "…" else "%.${digits}f".format(Locale.US, x)
}

/** A glyph for a WMO weather code. */
fun weatherGlyph(code: Int, day: Boolean = true): String = when (code) {
    0 -> if (day) "☀" else "☾"
    1, 2 -> if (day) "⛅" else "☁"
    3 -> "☁"
    45, 48 -> "🌫"
    in 51..57 -> "🌦"
    in 61..67, in 80..82 -> "🌧"
    in 71..77, 85, 86 -> "🌨"
    in 95..99 -> "⛈"
    else -> "☁"
}

fun weatherText(code: Int): String = when (code) {
    0 -> "clear"
    1 -> "mostly clear"
    2 -> "partly cloudy"
    3 -> "overcast"
    45, 48 -> "fog"
    in 51..55 -> "drizzle"
    56, 57 -> "freezing drizzle"
    61 -> "light rain"
    63 -> "rain"
    65 -> "heavy rain"
    66, 67 -> "freezing rain"
    in 71..77 -> "snow"
    80, 81 -> "showers"
    82 -> "violent showers"
    85, 86 -> "snow showers"
    in 95..99 -> "thunderstorm"
    else -> "—"
}
