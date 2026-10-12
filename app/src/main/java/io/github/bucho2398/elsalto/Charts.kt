package io.github.bucho2398.elsalto

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

object Palette {
    val bg = Color(0xFF15171C)
    val surface = Color(0xFF1E2128)
    val fg = Color(0xFFE6E8EE)
    val muted = Color(0xFF8A90A0)
    val accent = Color(0xFF7FD8A6)
    val sun = Color(0xFFF2CC60)
    val dry = Color(0xFF7FD8A6)
    val damp = Color(0xFFE8C468)
    val wet = Color(0xFFE8806A)
    val rain = Color(0xFF6AA8F0)
    val rainAhead = Color(0xFF7FDDF0)
    val terrain = Color(0x66C8CCD8)
    val shade = Color(0x996A8CF0)
    val june = Color(0xFFF29A5C)
    val december = Color(0xFF7FDDF0)

    fun state(s: String) = when (s) { "dry" -> dry; "damp" -> damp; else -> wet }
}

private fun DrawScope.label(text: String, x: Float, y: Float, sizeDp: Float, color: Color, align: android.graphics.Paint.Align = android.graphics.Paint.Align.LEFT, bold: Boolean = false) {
    drawIntoCanvas {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textSize = sizeDp * density
            textAlign = align
            isFakeBoldText = bold
        }
        // y is the text's middle.
        it.nativeCanvas.drawText(text, x, y - (p.descent() + p.ascent()) / 2, p)
    }
}

private val LEFT = android.graphics.Paint.Align.LEFT
private val CENTER = android.graphics.Paint.Align.CENTER
private val RIGHT = android.graphics.Paint.Align.RIGHT

/** Rain per hour from 72 h back to 72 h ahead; the chance of rain ahead as faint bars. */
@Composable
fun RainStrip(c: Conditions, height: Dp) {
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val hrs = c.hours
        val n = max(hrs.size, 1)
        val top = max(2.0, hrs.maxOfOrNull { it.rain } ?: 0.0)
        val bw = size.width / n
        val base = size.height - 14.dp.toPx()
        drawRect(Palette.muted.copy(alpha = 0.4f), Offset(0f, base), Size(size.width, 1.dp.toPx()))
        hrs.forEachIndexed { i, x ->
            if (i > c.nowIndex) {
                val ph = (x.prob / 100 * (base - 2)).toFloat()
                drawRect(Palette.rain.copy(alpha = 0.15f), Offset(i * bw, base - ph), Size(max(bw - 0.5f, 0.5f), ph))
            }
            val rh = (x.rain / top * (base - 2)).toFloat()
            drawRect(if (i > c.nowIndex) Palette.rainAhead else Palette.rain, Offset(i * bw, base - rh), Size(max(bw - 0.5f, 0.5f), rh))
        }
        drawRect(Palette.accent, Offset(c.nowIndex * bw, 0f), Size(1.5.dp.toPx(), base))
        val ly = size.height - 6.dp.toPx()
        label("−72h", 0f, ly, 10f, Palette.muted)
        label("now", c.nowIndex * bw, ly, 10f, Palette.accent, CENTER)
        label("+72h", size.width, ly, 10f, Palette.muted, RIGHT)
        label("${Fmt.mm(top)} mm/h", size.width, 8.dp.toPx(), 10f, Palette.muted, RIGHT)
    }
}

/** Today on a wall: sun on its face, terrain shadow, your shade windows, now. */
@Composable
fun DayBar(w: WallState, c: Conditions) {
    Canvas(Modifier.fillMaxWidth().height(46.dp)) {
        val hr = ((c.updated + c.offset) % 86400) / 3600.0
        fun x(h: Double) = (h / 24 * size.width).toFloat()
        val y = 4.dp.toPx(); val bh = 9.dp.toPx(); val r = CornerRadius(bh / 2)
        drawRoundRect(Palette.muted.copy(alpha = 0.2f), Offset(0f, y), Size(size.width, bh), r)
        w.terrainToday.forEach { drawRect(Palette.terrain, Offset(x(it[0]), y), Size(x(it[1] - it[0]), bh)) }
        w.shade.forEach {
            val len = ((it[1] - it[0] + 24) % 24).let { l -> if (l == 0) 24 else l }
            drawRect(Palette.shade, Offset(x(it[0].toDouble()), y), Size(x(len.toDouble()), bh))
        }
        w.sunToday.forEach { drawRoundRect(Palette.sun, Offset(x(it[0]), y), Size(x(it[1] - it[0]), bh), r) }
        for (t in listOf(0, 6, 12, 18, 24)) {
            drawRect(Palette.muted, Offset(x(t.toDouble()) - if (t == 24) 1f else 0f, y + bh + 1), Size(1f, 4.dp.toPx()))
            label("%02d".format(t), x(t.toDouble()), y + bh + 13.dp.toPx(), 9f, Palette.muted, if (t == 0) LEFT else if (t == 24) RIGHT else CENTER)
        }
        drawRect(Palette.fg, Offset(x(hr) - 1, 0f), Size(2.dp.toPx(), y + bh + 4.dp.toPx()))
        // Legend.
        val ly = size.height - 5.dp.toPx()
        var lx = 0f
        for ((col, text) in listOf(Palette.sun to "sun on face", Palette.terrain to "terrain shadow", Palette.shade to "your shade")) {
            drawRoundRect(col, Offset(lx, ly - 3.dp.toPx()), Size(10.dp.toPx(), 6.dp.toPx()), CornerRadius(2.dp.toPx()))
            label(text, lx + 14.dp.toPx(), ly, 9f, Palette.muted)
            lx += 14.dp.toPx() + text.length * 5.2f.dp.toPx() + 10.dp.toPx()
        }
        drawRect(Palette.fg, Offset(lx, ly - 4.dp.toPx()), Size(2.dp.toPx(), 8.dp.toPx()))
        label("now", lx + 6.dp.toPx(), ly, 9f, Palette.muted)
    }
}

/**
 * What the wall sees: the terrain horizon around it (grey), centred on the way it faces, the band of
 * directions the sun can hit the face from, and the sun's path today, on 21 June and on 21 December.
 */
@Composable
fun HorizonChart(w: WallState, c: Conditions) {
    Canvas(Modifier.fillMaxWidth().height(190.dp)) {
        val mid = w.deg ?: 180.0
        val top = 90.0
        val gh = size.height - 24.dp.toPx()
        fun x(az: Double) = (((az - mid + 540) % 360 + 360) % 360 / 360 * size.width).toFloat()
        fun y(el: Double) = (gh - max(el, 0.0) / top * gh).toFloat()
        drawRect(Palette.rain.copy(alpha = 0.06f), Offset.Zero, Size(size.width, gh))
        if (w.deg != null) {
            drawRect(Palette.sun.copy(alpha = 0.08f), Offset(93f / 360 * size.width, 0f), Size(174f / 360 * size.width, gh))
        }
        for (e in listOf(30.0, 60.0)) {
            drawRect(Palette.muted.copy(alpha = 0.25f), Offset(0f, y(e)), Size(size.width, 1f))
            label("${e.toInt()}°", 2f, y(e) - 7.dp.toPx(), 9f, Palette.muted)
        }
        val hz = w.horizon
        if (hz.isNotEmpty()) {
            val p = Path().apply {
                moveTo(0f, gh)
                for (k in 0..180) {
                    val az = mid - 180 + k * 2
                    val i = (((az % 360) + 360) % 360 / w.hzStep).toInt() % hz.size
                    lineTo(k * 2f / 360 * size.width, y(hz[i]))
                }
                lineTo(size.width, gh)
                close()
            }
            drawPath(p, Palette.terrain)
        }
        val paths = listOf(Triple("dec", Palette.december, "21 dec"), Triple("jun", Palette.june, "21 jun"), Triple("today", Palette.sun, "today"))
        for ((key, col, text) in paths) {
            val pts = c.paths[key] ?: continue
            val rr = if (key == "today") 2.6.dp.toPx() else 1.8.dp.toPx()
            pts.forEach { drawCircle(col, rr, Offset(x(it[0]), y(it[1]))) }
            pts.maxByOrNull { it[1] }?.let {
                label(text, x(it[0]), max(y(min(it[1], 86.0)) - 11.dp.toPx(), 8.dp.toPx()), 10f, col, CENTER, bold = true)
            }
        }
        if (c.sunNow[1] > 0) drawCircle(Palette.fg, 6.dp.toPx(), Offset(x(c.sunNow[0]), y(c.sunNow[1])), style = Stroke(2.dp.toPx()))
        val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        for (k in 0 until 8) {
            val xx = x(k * 45.0)
            drawRect(Palette.muted, Offset(xx, gh), Size(1f, 4.dp.toPx()))
            val facing = w.deg != null && ((Math.round(w.deg / 45) % 8).toInt() == k)
            label(names[k], xx, gh + 14.dp.toPx(), 10f, if (facing) Palette.accent else Palette.muted, CENTER)
        }
    }
}

/** Sun on the face through the year: a row per month (the 15th), hours across; terrain shadow in grey. */
@Composable
fun SunYearChart(w: WallState, month: Int) {
    Canvas(Modifier.fillMaxWidth().height((12 * 15 + 20).dp)) {
        val lx = 16.dp.toPx(); val rx = 48.dp.toPx()
        val bw = size.width - lx - rx
        val row = 15.dp.toPx(); val bh = 10.dp.toPx()
        fun x(h: Double) = (lx + h / 24 * bw).toFloat()
        w.year.forEachIndexed { i, m ->
            val y = i * row
            drawRect(Palette.muted.copy(alpha = 0.15f), Offset(lx, y + 2), Size(bw, bh))
            m.terrain.forEach { drawRect(Palette.terrain, Offset(x(it[0]), y + 2), Size((it[1] - it[0]).toFloat() / 24 * bw, bh)) }
            m.spans.forEach { drawRect(Palette.sun, Offset(x(it[0]), y + 2), Size((it[1] - it[0]).toFloat() / 24 * bw, bh)) }
            val now = i == month
            label("JFMAMJJASOND"[i].toString(), 0f, y + 2 + bh / 2, 10f, if (now) Palette.accent else Palette.muted, bold = now)
            label("${m.hours} h", size.width, y + 2 + bh / 2, 10f, if (now) Palette.accent else Palette.muted, RIGHT)
        }
        val ty = 12 * row + 2
        for (t in listOf(0, 6, 12, 18, 24)) {
            drawRect(Palette.muted, Offset(x(t.toDouble()), ty), Size(1f, 4.dp.toPx()))
            label("%02d".format(t), x(t.toDouble()), ty + 12.dp.toPx(), 9f, Palette.muted, if (t == 0) LEFT else if (t == 24) RIGHT else CENTER)
        }
    }
}
