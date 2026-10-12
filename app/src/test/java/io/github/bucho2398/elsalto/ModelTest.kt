package io.github.bucho2398.elsalto

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The app's model must give what the reference model (the Python one in the omalauncher widget,
 * tools-side) gives for the same weather at the same moment: expected.json was made from site.json
 * and walls.json by that model.
 */
class ModelTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun matchesReferenceModel() {
        val crag = CragStore.parse(File("../data/crag.json").readText(), File("../data/terrain.json").readText())
        val site = WeatherStore.parseSite(res("site.json"))
        assertNotNull("site weather parses", site)
        val points = WeatherStore.parsePoints(res("walls.json"))!!
        val elevs = crag.terrain.values.map { it.elev }.sorted()
        val keys = crag.walls.mapNotNull { Model.pointKey(it, crag.terrain[it.id], elevs[elevs.size / 2]) }.distinct()
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
        assertEquals("weather points", points.size, keys.size)
        val exp = JSONObject(res("expected.json"))
        val c = Model.compute(crag, WallPrefs.None, site!!, keys.zip(points).toMap(), exp.getLong("now"))

        val past = exp.getJSONArray("rainPast")
        for (i in 0..2) assertEquals("rain past", past.getDouble(i), c.rainPast[i], 0.051)
        val ew = exp.getJSONObject("walls")
        assertEquals("wall count", ew.length(), c.walls.size)
        val problems = mutableListOf<String>()
        for (w in c.walls) {
            val e = ew.getJSONObject(w.wall.id)
            fun check(what: String, ok: Boolean, got: Any?, want: Any?) { if (!ok) problems += "${w.wall.name}: $what $got, expected $want" }
            check("wet %", abs(w.wetPct - e.getInt("wetPct")) <= 1, w.wetPct, e.getInt("wetPct"))
            check("state", w.state == e.getString("state"), w.state, e.getString("state"))
            check("sun h", abs(w.sunHours - e.getDouble("sunHours")) <= 0.21, w.sunHours, e.getDouble("sunHours"))
            check("terrain h", abs(hoursOf(w.terrainToday) - e.getDouble("terrainHours")) <= 0.21, hoursOf(w.terrainToday), e.getDouble("terrainHours"))
            check("aspect", w.aspect == (if (e.isNull("aspect")) null else e.getString("aspect")), w.aspect, e.opt("aspect"))
            check("wind %", abs(w.windExpo - e.getInt("windExpo")) <= 1, w.windExpo, e.getInt("windExpo"))
            check("sky %", abs(w.sky - e.getInt("sky")) <= 1, w.sky, e.getInt("sky"))
            check("now", w.now == e.getString("now"), w.now, e.getString("now"))
            val yh = e.getJSONArray("yearHours")
            for (m in 0 until 12) check("month ${m + 1} sun h", abs(w.year[m].hours - yh.getDouble(m)) <= 0.3, w.year[m].hours, yh.getDouble(m))
        }
        // After the fixture's forecast rain, so the drying physics is compared too.
        val later = exp.getJSONObject("later")
        val lw = later.getJSONObject("walls")
        for (w in Model.compute(crag, WallPrefs.None, site, keys.zip(points).toMap(), later.getLong("now")).walls) {
            val e = lw.getJSONObject(w.wall.id)
            if (abs(w.wetPct - e.getInt("wetPct")) > 1) problems += "${w.wall.name}: later wet % ${w.wetPct}, expected ${e.getInt("wetPct")}"
            if (w.state != e.getString("state")) problems += "${w.wall.name}: later state ${w.state}, expected ${e.getString("state")}"
            val wantDry = if (e.isNull("dryAt")) null else e.getLong("dryAt")
            if (w.dryAt != wantDry) problems += "${w.wall.name}: later dry at ${w.dryAt}, expected $wantDry"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
