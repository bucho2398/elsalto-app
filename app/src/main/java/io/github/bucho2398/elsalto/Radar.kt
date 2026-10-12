package io.github.bucho2398.elsalto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

class RadarFrame(val time: Long, val bitmap: Bitmap)
/** [rainy] is false when no frame has rain in view: the frames are then identical. */
class RadarResult(val frames: List<RadarFrame>, val cloudsTime: Long?, val km: Int, val rainy: Boolean)

/**
 * The last hour of rain radar around El Salto: an Esri dark-grey map with labels, GOES-East GeoColor
 * clouds from NASA GIBS (bright pixels only, so clear ground shows the map) and RainViewer radar
 * (its highest zoom is 7), with a ring on El Salto.
 */
object Radar {
    private const val Z = 7
    private const val SIZE = 600
    private const val SPAN = 2.4          // tiles across at Z, ~680 km
    private const val FRAMES = 6

    private fun tileXY(lat: Double, lon: Double): Pair<Double, Double> {
        val n = 1 shl Z
        val x = (lon + 180) / 360 * n
        val y = (1 - asinh(tan(lat * PI / 180)) / PI) / 2 * n
        return x to y
    }

    private fun mosaic(urlFor: (Int, Int) -> String, cx: Double, cy: Double): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val x0 = cx - SPAN / 2
        val y0 = cy - SPAN / 2
        val scale = SIZE / (SPAN * 256)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (tx in floor(x0).toInt()..floor(x0 + SPAN).toInt()) {
            for (ty in floor(y0).toInt()..floor(y0 + SPAN).toInt()) {
                val bytes = Net.bytes(urlFor(tx, ty), 15000) ?: continue
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                val left = ((tx - x0) * 256 * scale).toFloat()
                val top = ((ty - y0) * 256 * scale).toFloat()
                val sz = (256 * scale).toFloat()
                c.drawBitmap(bmp, null, RectF(left, top, left + sz, top + sz), paint)
                bmp.recycle()
            }
        }
        return out
    }

    private fun clouds(cx: Double, cy: Double): Pair<Bitmap, Long>? {
        val r = 20037508.342789244
        val n = (1 shl Z).toDouble()
        fun mx(x: Double) = x / n * 2 * r - r
        fun my(y: Double) = r - y / n * 2 * r
        val x0 = mx(cx - SPAN / 2); val x1 = mx(cx + SPAN / 2)
        val y1 = my(cy - SPAN / 2); val y0 = my(cy + SPAN / 2)
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:00'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        // GIBS publishes GOES-East every 10 minutes, a little late, and some slots come back empty.
        for (back in 20..120 step 10) {
            val t = (System.currentTimeMillis() / 1000 - back * 60L) / 600 * 600
            val url = "https://gibs.earthdata.nasa.gov/wms/epsg3857/best/wms.cgi?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0" +
                "&LAYERS=GOES-East_ABI_GeoColor&STYLES=&CRS=EPSG:3857&BBOX=$x0,$y0,$x1,$y1" +
                "&WIDTH=$SIZE&HEIGHT=$SIZE&FORMAT=image/png&TRANSPARENT=true&TIME=${fmt.format(Date(t * 1000))}"
            val bytes = Net.bytes(url, 25000) ?: continue
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            val w = src.width; val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            var covered = 0; var brightest = 0
            for (i in px.indices) {
                val p = px[i]
                val a = Color.alpha(p)
                if (a > 0) covered++
                val lum = (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).roundToInt()
                if (lum > brightest) brightest = lum
                // Clouds are bright: keep those, fade clear ground out.
                val ca = if (lum < 90) 0 else min(150, ((lum - 90) * 1.4).roundToInt())
                px[i] = Color.argb(ca * a / 255, Color.red(p), Color.green(p), Color.blue(p))
            }
            src.recycle()
            if (covered < 0.97 * px.size || brightest <= 40) continue
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888) to t
        }
        return null
    }

    /** Radar pixels solid enough to see; RainViewer's smoothing leaves a faint fringe and specks. */
    private fun rainPixels(b: Bitmap): Int {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.count { Color.alpha(it) >= 64 }
    }

    fun load(): RadarResult? {
        val (cx, cy) = tileXY(SITE_LAT, SITE_LON)
        val maps = Net.text("https://api.rainviewer.com/public/weather-maps.json")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val host = maps.optString("host", "https://tilecache.rainviewer.com")
        val past = maps.optJSONObject("radar")?.optJSONArray("past") ?: return null
        val esri = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/"
        val base = mosaic({ x, y -> "${esri}World_Dark_Gray_Base/MapServer/tile/$Z/$y/$x" }, cx, cy)
        val labels = mosaic({ x, y -> "${esri}World_Dark_Gray_Reference/MapServer/tile/$Z/$y/$x" }, cx, cy)
        val cloud = clouds(cx, cy)
        val under = base.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(under).apply {
            cloud?.first?.let { drawBitmap(it, 0f, 0f, null) }
            drawBitmap(labels, 0f, 0f, null)
        }
        base.recycle(); labels.recycle(); cloud?.first?.recycle()

        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 90, 90); style = Paint.Style.STROKE; strokeWidth = 3f }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 90, 90) }
        var rainPx = 0
        val frames = (maxOf(0, past.length() - FRAMES) until past.length()).map { i ->
            val p = past.getJSONObject(i)
            val img = under.copy(Bitmap.Config.ARGB_8888, true)
            // Colour scheme 2 (universal blue), smoothed, snow shown.
            val rain = mosaic({ x, y -> "$host${p.getString("path")}/256/$Z/$x/$y/2/1_1.png" }, cx, cy)
            Canvas(img).apply {
                drawBitmap(rain, 0f, 0f, null)
                drawCircle(SIZE / 2f, SIZE / 2f, 9f, ring)
                drawCircle(SIZE / 2f, SIZE / 2f, 3f, dot)
            }
            rainPx = maxOf(rainPx, rainPixels(rain))
            rain.recycle()
            RadarFrame(p.getLong("time"), img)
        }
        under.recycle()
        val km = (SPAN * 40075 * cos(SITE_LAT * PI / 180) / (1 shl Z)).roundToInt()
        return RadarResult(frames, cloud?.second, km, rainy = rainPx >= 20)
    }
}
