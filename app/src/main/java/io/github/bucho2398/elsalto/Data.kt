package io.github.bucho2398.elsalto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A route on a wall. */
data class Route(val id: String, val name: String, val grade: String, val stars: Int, val pitches: Int)

/** A wall: where it is, which way it faces, its tags and routes; [url] is its theCrag page, if it has one. */
data class Wall(
    val id: String,
    val name: String,
    val zone: String,
    val url: String,
    val aspect: String?,
    val deg: Double?,
    val lat: Double?,
    val lon: Double?,
    val routes: List<Route>,
    val count: Int,
    val tags: Set<String>,
    val features: Map<String, Boolean>,
)

/** What the elevation data says about a wall: see tools/terrain.py in elsalto-data. */
data class Terrain(
    val elev: Double,
    val slope: Double,
    val facing: Double,
    val reliable: Boolean,
    val catchment: Double,
    val svf: Double,
    val horizon: DoubleArray,
)

class CragData(
    val walls: List<Wall>,
    val zones: List<String>,
    /** When walls.json last changed (Unix seconds), 0 if unknown. */
    val updated: Long,
    val terrain: Map<String, Terrain>,
    val terrainStep: Double,
    /** The published version (index.json), 0 for data without one. */
    val version: Long = 0,
    /** Who the walls and routes come from, as the data says it. */
    val attribution: String? = null,
) {
    companion object {
        val EMPTY = CragData(emptyList(), emptyList(), 0, emptyMap(), 2.0)
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

/**
 * The walls, routes and terrain. They're edited by hand in the elsalto-data repository and
 * published on GitHub Pages ([BuildConfig.DATA_URL]): index.json gives a version and each file's
 * SHA-256. [update] downloads a newer version, checks every file against its checksum and that it
 * parses, and only then swaps it in, so a bad or partial download never replaces good data.
 * [load] uses the newest of the downloaded copy and the one bundled in the APK (if the build had
 * one), so the app works offline and on first start.
 */
object CragStore {
    private val FILES = listOf("walls.json", "terrain.json")
    /** How often the app and widget look for new data unless asked to (manual refresh). */
    private const val CHECK_EVERY_MS = 3 * 3600_000L

    @Volatile private var cached: CragData? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "data")

    private class Copy(val version: Long, val read: (String) -> ByteArray?)

    private fun downloaded(ctx: Context): Copy? {
        val d = dir(ctx)
        val idx = runCatching { JSONObject(File(d, "index.json").readText()) }.getOrNull() ?: return null
        return Copy(idx.optLong("version")) { name -> runCatching { File(d, name).readBytes() }.getOrNull() }
    }

    private fun bundled(ctx: Context): Copy? {
        val idx = runCatching { ctx.assets.open("index.json").use { JSONObject(it.reader().readText()) } }.getOrNull() ?: return null
        return Copy(idx.optLong("version")) { name -> runCatching { ctx.assets.open(name).use { it.readBytes() } }.getOrNull() }
    }

    private fun parseCopy(c: Copy): CragData? = runCatching {
        val walls = c.read("walls.json") ?: return null
        parse(walls.toString(Charsets.UTF_8), c.read("terrain.json")?.toString(Charsets.UTF_8), c.version)
    }.getOrNull()?.takeIf { it.walls.isNotEmpty() }

    /** The newest walls and terrain on the phone, or [CragData.EMPTY] before the first download. */
    fun load(ctx: Context): CragData {
        cached?.let { return it }
        val copies = listOfNotNull(downloaded(ctx), bundled(ctx)).sortedByDescending { it.version }
        val data = copies.firstNotNullOfOrNull { parseCopy(it) } ?: CragData.EMPTY
        if (data !== CragData.EMPTY) cached = data
        return data
    }

    /**
     * Looks for newer data, at most every few hours unless [force]. Returns true when new data was
     * installed. Any failure (offline, bad checksum, a file that doesn't parse) leaves things as they were.
     */
    @Synchronized
    fun update(ctx: Context, force: Boolean = false): Boolean {
        val sp = ctx.getSharedPreferences("data", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong("checked", 0) < CHECK_EVERY_MS) return false
        val base = BuildConfig.DATA_URL
        val indexText = Net.text(base + "index.json?t=" + now / 60000) ?: return false
        sp.edit().putLong("checked", now).apply()
        val index = runCatching { JSONObject(indexText) }.getOrNull() ?: return false
        if (index.optInt("schema") != 1) return false
        val version = index.optLong("version")
        if (version <= load(ctx).version) return false
        val files = index.optJSONObject("files") ?: return false
        val have = listOfNotNull(downloaded(ctx), bundled(ctx))
        val got = mutableMapOf<String, ByteArray>()
        for (name in FILES) {
            val sha = files.optJSONObject(name)?.optString("sha256")?.lowercase() ?: return false
            // Reuse a file we already have when it hasn't changed.
            val bytes = have.firstNotNullOfOrNull { c -> c.read(name)?.takeIf { sha256(it) == sha } }
                ?: Net.bytes(base + name + "?v=" + sha.take(16))?.takeIf { sha256(it) == sha }
                ?: return false
            got[name] = bytes
        }
        val data = runCatching {
            parse(got.getValue("walls.json").toString(Charsets.UTF_8), got.getValue("terrain.json").toString(Charsets.UTF_8), version)
        }.getOrNull()?.takeIf { it.walls.isNotEmpty() } ?: return false

        // Write the new copy beside the old one, then swap the directories.
        val d = dir(ctx)
        val fresh = File(ctx.filesDir, "data.new").apply { deleteRecursively(); mkdirs() }
        for ((name, bytes) in got) File(fresh, name).writeBytes(bytes)
        File(fresh, "index.json").writeText(indexText)
        val old = File(ctx.filesDir, "data.old").apply { deleteRecursively() }
        if (d.exists() && !d.renameTo(old)) return false
        if (!fresh.renameTo(d)) { old.renameTo(d); return false }
        old.deleteRecursively()
        cached = data
        return true
    }

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun parse(cragText: String, terrainText: String?, version: Long = 0): CragData {
        val crag = JSONObject(cragText)
        val terrainJson = terrainText?.let { runCatching { JSONObject(it) }.getOrNull() }

        val walls = mutableListOf<Wall>()
        val arr = crag.getJSONArray("walls")
        for (i in 0 until arr.length()) {
            val w = arr.getJSONObject(i)
            val pos = w.optJSONArray("pos")
            val rs = w.optJSONArray("routes") ?: JSONArray()
            val routes = (0 until rs.length()).map {
                val r = rs.getJSONObject(it)
                Route(r.optString("id"), r.optString("name"), r.optString("grade"), r.optInt("stars"), r.optInt("pitches", 1))
            }
            val feats = w.optJSONObject("features")
            walls += Wall(
                id = w.getString("id"),
                name = w.getString("name"),
                zone = w.optString("zone"),
                url = w.optString("url"),
                aspect = w.optStringOrNull("aspect"),
                deg = w.optDoubleOrNull("deg"),
                lat = pos?.optDouble(0),
                lon = pos?.optDouble(1),
                routes = routes,
                count = w.optInt("count", routes.size).takeIf { it > 0 } ?: routes.size,
                tags = (w.optJSONArray("tags") ?: JSONArray()).strings().toSet(),
                features = feats?.keys()?.asSequence()?.associateWith { feats.optBoolean(it) } ?: emptyMap(),
            )
        }

        val terrain = mutableMapOf<String, Terrain>()
        terrainJson?.optJSONObject("walls")?.let { tw ->
            for (id in tw.keys()) {
                val t = tw.getJSONObject(id)
                val hz = t.optJSONArray("horizon") ?: JSONArray()
                terrain[id] = Terrain(
                    elev = t.optDouble("elev"),
                    slope = t.optDouble("slope"),
                    facing = t.optDouble("facing"),
                    reliable = t.optBoolean("reliable"),
                    catchment = t.optDouble("catchment", 0.0),
                    svf = t.optDouble("svf", 0.8),
                    horizon = DoubleArray(hz.length()) { hz.getDouble(it) },
                )
            }
        }
        val zones = (crag.optJSONArray("zones") ?: JSONArray()).let { z ->
            (0 until z.length()).map { z.getJSONObject(it).getString("name") }
        }
        return CragData(
            walls, zones, crag.optLong("updated", crag.optLong("fetched")), terrain, terrainJson?.optDouble("step", 2.0) ?: 2.0,
            version, crag.optStringOrNull("attribution"),
        )
    }
}

/** What the model needs to know of your choices per wall. */
interface WallPrefs {
    fun fav(id: String): Boolean
    fun aspect(id: String): String?
    fun shade(id: String): List<IntArray>

    object None : WallPrefs {
        override fun fav(id: String) = false
        override fun aspect(id: String): String? = null
        override fun shade(id: String): List<IntArray> = emptyList()
    }
}

/** Your choices per wall (by wall id): favourite, the way it faces, shade windows. Kept on the phone. */
class Prefs(ctx: Context) : WallPrefs {
    private val sp = ctx.getSharedPreferences("walls", Context.MODE_PRIVATE)

    private fun obj(id: String): JSONObject = JSONObject(sp.getString(id, "{}") ?: "{}")
    private fun save(id: String, o: JSONObject) {
        sp.edit().apply { if (o.length() == 0) remove(id) else putString(id, o.toString()) }.apply()
    }

    override fun fav(id: String) = obj(id).optBoolean("fav")
    override fun aspect(id: String): String? = obj(id).optStringOrNull("aspect")
    override fun shade(id: String): List<IntArray> = obj(id).optJSONArray("shade")?.let { a ->
        (0 until a.length()).map { val p = a.getJSONArray(it); intArrayOf(p.getInt(0), p.getInt(1)) }
    } ?: emptyList()

    fun toggleFav(id: String) = save(id, obj(id).apply { if (optBoolean("fav")) remove("fav") else put("fav", true) })

    fun setAspect(id: String, dir: String?) = save(id, obj(id).apply { if (dir == null) remove("aspect") else put("aspect", dir) })

    fun addShade(id: String, from: Int, to: Int) {
        if (from % 24 == to % 24) return
        save(id, obj(id).apply {
            val a = optJSONArray("shade") ?: JSONArray()
            a.put(JSONArray().put(from % 24).put(if (to == 24) 24 else to % 24))
            put("shade", a)
        })
    }

    fun clearShade(id: String) = save(id, obj(id).apply { remove("shade") })
}
