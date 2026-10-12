package io.github.bucho2398.elsalto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A route on a wall, from theCrag. */
data class Route(val id: String, val name: String, val grade: String, val stars: Int, val pitches: Int)

/** A wall (theCrag area that holds routes): where it is, which way it faces, its tags and routes. */
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

/** What the elevation data says about a wall: see tools/terrain.py. */
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
    val fetched: Long,
    val terrain: Map<String, Terrain>,
    val terrainStep: Double,
)

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

object CragStore {
    @Volatile private var cached: CragData? = null

    /** The walls and terrain bundled in the app (data/ in the repository). */
    fun load(ctx: Context): CragData {
        cached?.let { return it }
        val crag = ctx.assets.open("crag.json").bufferedReader().use { it.readText() }
        val terrain = runCatching { ctx.assets.open("terrain.json").bufferedReader().use { it.readText() } }.getOrNull()
        return parse(crag, terrain).also { cached = it }
    }

    fun parse(cragText: String, terrainText: String?): CragData {
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
        return CragData(walls, zones, crag.optLong("fetched"), terrain, terrainJson?.optDouble("step", 2.0) ?: 2.0)
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

/** Your choices per wall (theCrag id): favourite, the way it faces, shade windows. Kept on the phone. */
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
