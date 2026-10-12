package io.github.bucho2398.elsalto

import android.content.Context

/** Loads the weather (cached) and runs the model; shared by the app and the home-screen widget. */
object Repo {
    fun conditions(ctx: Context, force: Boolean = false, offline: Boolean = false): Conditions? {
        val crag = CragStore.load(ctx)
        val prefs = Prefs(ctx)
        val located = crag.walls.filter { it.lat != null && it.lon != null }
        val lat = if (located.isEmpty()) SITE_LAT else located.sumOf { it.lat!! } / located.size
        val lon = if (located.isEmpty()) SITE_LON else located.sumOf { it.lon!! } / located.size
        val elevs = crag.terrain.values.map { it.elev }.sorted()
        val median = if (elevs.isEmpty()) 1500.0 else elevs[elevs.size / 2]
        val site = WeatherStore.site(ctx, lat, lon, if (elevs.isEmpty()) null else median, force && !offline) ?: return null
        val keys = crag.walls.mapNotNull { Model.pointKey(it, crag.terrain[it.id], median) }.distinct().sortedWith(
            compareBy({ it.first }, { it.second }, { it.third }),
        )
        val pts = WeatherStore.points(ctx, keys, force && !offline)
        val local = if (pts != null && pts.size == keys.size) keys.zip(pts).toMap() else emptyMap()
        return Model.compute(crag, prefs, site, local, System.currentTimeMillis() / 1000)
    }
}
