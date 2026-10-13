package io.github.bucho2398.elsalto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.max

/** How a route went, from least to most. */
enum class Ascent(val label: String) { NO_TOP("sin top"), TOP("top"), FALLS("vuelos"), SEND("encadenada") }

/** Your log of a route: done, how, falls (for [Ascent.FALLS]), a note, whether it has a photo. */
data class RouteEntry(
    val done: Boolean = false,
    val ascent: Ascent? = null,
    val falls: Int = 1,
    val note: String = "",
    val photo: Boolean = false,
) {
    val empty get() = !done && ascent == null && note.isEmpty() && !photo
    val ascentText get() = when (ascent) {
        null -> ""
        Ascent.FALLS -> "$falls ${if (falls == 1) "vuelo" else "vuelos"}"
        else -> ascent.label
    }
}

/**
 * Your routes, keyed by route id (kept stable in the hand-maintained elsalto-data). Kept on the phone:
 * entries in shared preferences, photos as JPEGs in the app's files.
 */
class RouteLog(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("routes", Context.MODE_PRIVATE)
    private val entries = mutableStateMapOf<String, RouteEntry>()
    private val dir = File(ctx.filesDir, "photos")

    init {
        for ((id, v) in sp.all) runCatching { entries[id] = fromJson(JSONObject(v as String)) }
    }

    private fun fromJson(o: JSONObject) = RouteEntry(
        done = o.optBoolean("done"),
        ascent = o.optString("ascent").takeIf { it.isNotEmpty() }?.let { a -> Ascent.entries.firstOrNull { it.name == a } },
        falls = o.optInt("falls", 1).coerceAtLeast(1),
        note = o.optString("note"),
        photo = o.optBoolean("photo"),
    )

    private fun toJson(e: RouteEntry) = JSONObject().put("done", e.done).put("falls", e.falls).put("note", e.note).put("photo", e.photo)
        .apply { e.ascent?.let { put("ascent", it.name) } }

    operator fun get(id: String) = entries[id] ?: RouteEntry()

    fun update(id: String, change: (RouteEntry) -> RouteEntry) {
        val e = change(get(id))
        if (e.empty) {
            entries.remove(id); sp.edit().remove(id).apply()
            return
        }
        entries[id] = e
        sp.edit().putString(id, toJson(e).toString()).apply()
    }

    /** Writes every entry, photos included (base64 JPEG), as one JSON file. Returns how many. Call off the main thread. */
    fun export(out: OutputStream): Int {
        val routes = JSONObject()
        for ((id, e) in entries.toMap()) {
            val o = toJson(e)
            val f = photoFile(id)
            if (e.photo && f.exists()) o.put("photoJpeg", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)) else o.put("photo", false)
            routes.put(id, o)
        }
        val doc = JSONObject().put("app", "elsalto-routes").put("version", 1).put("exported", System.currentTimeMillis() / 1000).put("routes", routes)
        out.bufferedWriter().use { it.write(doc.toString()) }
        return routes.length()
    }

    /**
     * Reads a file from [export], writing its photos. A route in the file replaces the one here
     * (keeping this phone's photo if the file has none); routes only here stay. Null if it isn't
     * an export. Call off the main thread, then [applyImport] on the main thread.
     */
    fun readImport(input: InputStream): Map<String, RouteEntry>? {
        val doc = runCatching { JSONObject(input.bufferedReader().use { it.readText() }) }.getOrNull() ?: return null
        if (doc.optString("app") != "elsalto-routes") return null
        val routes = doc.optJSONObject("routes") ?: return null
        val read = mutableMapOf<String, RouteEntry>()
        for (id in routes.keys()) {
            if (!id.matches(Regex("[A-Za-z0-9_-]+"))) continue   // ids name the photo files
            val o = routes.optJSONObject(id) ?: continue
            var e = fromJson(o)
            val jpeg = o.optString("photoJpeg")
            if (jpeg.isNotEmpty()) {
                val ok = runCatching { dir.mkdirs(); photoFile(id).writeBytes(Base64.decode(jpeg, Base64.NO_WRAP)) }.isSuccess
                e = e.copy(photo = ok)
            } else e = e.copy(photo = get(id).photo && photoFile(id).exists())
            read[id] = e
        }
        return read
    }

    fun applyImport(read: Map<String, RouteEntry>) {
        for ((id, e) in read) update(id) { e }
    }

    fun photoFile(id: String) = File(dir, "$id.jpg")

    /** Copies a picked image in, scaled to at most 1600 px on its long side. Call off the main thread. */
    fun setPhoto(id: String, uri: Uri): Boolean {
        val bmp = decode(uri, 1600) ?: return false
        dir.mkdirs()
        photoFile(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        return true
    }

    fun removePhoto(id: String) {
        photoFile(id).delete()
        update(id) { it.copy(photo = false) }
    }

    private fun decode(uri: Uri, maxSide: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder applies the camera's EXIF rotation.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                val s = max(info.size.width, info.size.height)
                if (s > maxSide) d.setTargetSize(info.size.width * maxSide / s, info.size.height * maxSide / s)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
    }.getOrNull()
}
