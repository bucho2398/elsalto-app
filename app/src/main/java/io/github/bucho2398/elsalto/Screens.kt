package io.github.bucho2398.elsalto

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun App(openUrl: (String) -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent, background = Palette.bg, surface = Palette.surface,
            onBackground = Palette.fg, onSurface = Palette.fg,
        ),
    ) {
        val vm: MainVm = viewModel()
        // Swipe left and right between the tabs, or tap one.
        val pager = rememberPagerState { 3 }
        val scope = rememberCoroutineScope()
        val tab = pager.currentPage
        Column(Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("El Salto", color = Palette.accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    val c = vm.conditions
                    Text(
                        when {
                            vm.loading -> "updating…"
                            c != null -> "climbing conditions · ${Fmt.time(c.updated, c.offset, "EEE HH:mm")}"
                            else -> vm.error ?: "loading…"
                        },
                        color = Palette.muted, fontSize = 12.sp,
                    )
                }
                if (vm.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                TextButton(onClick = { if (tab == 2) vm.loadRadar(true) else vm.refresh(true) }) { Text("refresh") }
            }
            TabRow(selectedTabIndex = tab, containerColor = Palette.bg, contentColor = Palette.accent) {
                listOf("conditions", "walls", "radar").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { scope.launch { pager.animateScrollToPage(i) } }, text = { Text(t) })
                }
            }
            val c = vm.conditions
            when {
                c == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (vm.loading) CircularProgressIndicator() else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(vm.error ?: "No weather yet", color = Palette.muted)
                        TextButton(onClick = { vm.refresh(true) }) { Text("retry") }
                    }
                }
                else -> HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                    when (page) {
                        0 -> ConditionsTab(c, openUrl)
                        1 -> WallsTab(c, vm, openUrl)
                        else -> RadarTab(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, color = Palette.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)

@Composable
private fun Small(text: String, color: Color = Palette.muted) = Text(text, color = color, fontSize = 12.sp, lineHeight = 16.sp)

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, tint: Color = Palette.fg) {
    Column(modifier) {
        Text(label, color = Palette.muted, fontSize = 12.sp, maxLines = 1)
        Text(value, color = tint, fontSize = 17.sp, maxLines = 1)
    }
}

@Composable
private fun StatGrid(vararg cells: Triple<String, String, Color>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.toList().chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (l, v, t) -> Stat(l, v, Modifier.weight(1f), t) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

fun headline(c: Conditions): String {
    val favs = c.walls.filter { it.fav }
    val pool = favs.ifEmpty { c.walls.filter { it.wall.count > 0 } }
    return when {
        c.fog -> "fog: exposed walls collect condensation"
        c.rainNext[0] >= 1 -> "rain coming: ${Fmt.mm(c.rainNext[0])} mm in 24 h"
        else -> "${pool.count { it.state == "dry" }} of ${pool.size} ${if (favs.isNotEmpty()) "favourite" else ""} walls dry".replace("  ", " ")
    }
}

@Composable
private fun ConditionsTab(c: Conditions, openUrl: (String) -> Unit) {
    val cur = c.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(weatherGlyph(cur.optInt("weather_code"), cur.optInt("is_day") == 1), fontSize = 44.sp, modifier = Modifier.width(72.dp))
                Column {
                    Text("${Fmt.num(cur.optDouble("temperature_2m"))}°C", color = Palette.rainAhead, fontSize = 36.sp)
                    Small("${weatherText(cur.optInt("weather_code"))} · feels ${Fmt.num(cur.optDouble("apparent_temperature"), 0)}° · dew point ${Fmt.num(cur.optDouble("dew_point_2m"))}° · ${c.site.elevation.roundToInt()} m")
                }
            }
        }
        item { Text(headline(c), color = if (c.fog || c.rainNext[0] >= 1) Palette.damp else Palette.fg, fontSize = 15.sp) }
        item {
            StatGrid(
                Triple("humidity", "${cur.optInt("relative_humidity_2m")}%", Palette.fg),
                Triple("wind", "${cur.optDouble("wind_speed_10m").roundToInt()} km/h", Palette.fg),
                Triple("gusts", "${cur.optDouble("wind_gusts_10m").roundToInt()} km/h", Palette.fg),
                Triple("clouds", "${cur.optInt("cloud_cover")}%", Palette.fg),
                Triple("sunrise", Fmt.time(c.sunrise, c.offset, "HH:mm"), Palette.sun),
                Triple("sunset", Fmt.time(c.sunset, c.offset, "HH:mm"), Palette.june),
            )
        }
        item { HorizontalDivider(color = Palette.muted.copy(alpha = 0.25f)) }
        item { SectionTitle("rain, mm") }
        item {
            StatGrid(
                Triple("last 24 h", Fmt.mm(c.rainPast[0]), Palette.rain), Triple("last 48 h", Fmt.mm(c.rainPast[1]), Palette.rain),
                Triple("last 72 h", Fmt.mm(c.rainPast[2]), Palette.rain), Triple("next 24 h", Fmt.mm(c.rainNext[0]), Palette.rainAhead),
                Triple("next 48 h", Fmt.mm(c.rainNext[1]), Palette.rainAhead), Triple("next 72 h", Fmt.mm(c.rainNext[2]), Palette.rainAhead),
            )
        }
        item {
            Small(
                "last rain ${c.lastRain?.let { Fmt.time(it, c.offset, "EEE HH:mm").lowercase() } ?: "over 3 days ago"} · " +
                    "next ${c.nextRain?.let { Fmt.time(it, c.offset, "EEE HH:mm").lowercase() } ?: "none in 3 days"}",
            )
        }
        item { RainStrip(c, 100.dp) }
        item { HorizontalDivider(color = Palette.muted.copy(alpha = 0.25f)) }
        item { SectionTitle("days · rain mm · chance · sunrise–sunset") }
        items(c.site.days) { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.time(d.t + 43200, c.offset, "EEE d").lowercase(), color = Palette.fg, modifier = Modifier.width(64.dp))
                Text(weatherGlyph(d.code), modifier = Modifier.width(32.dp))
                Text("${d.min.roundToInt()}–${d.max.roundToInt()}°", color = Palette.fg, fontSize = 14.sp, modifier = Modifier.width(72.dp))
                Text("${Fmt.mm(d.rain)}  ${d.prob.roundToInt()}%", color = Palette.rain, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text("${Fmt.time(d.sunrise, c.offset, "HH:mm")}–${Fmt.time(d.sunset, c.offset, "HH:mm")}", color = Palette.muted, fontSize = 13.sp,
                    textAlign = TextAlign.End, modifier = Modifier.width(100.dp))
            }
        }
        item { HorizontalDivider(color = Palette.muted.copy(alpha = 0.25f)) }
        item {
            Small(
                "Sun on each wall comes from its GPS point on theCrag, SRTM terrain elevation (a 360° horizon out to 8 km) and the sun's " +
                    "position (suncalc: Meeus, with refraction), so ridges and the canyon's other side cast their shadows. Rock wetness is an estimate per wall, hour by hour, " +
                    "with the weather at that wall's spot and elevation: rain reaches a face from above (slabs), blown onto it by the wind " +
                    "(ISO 15927-3, by wind direction against the face and how sheltered the canyon is upwind) and as runoff from the slope " +
                    "above; overhangs, roofs and caves catch little (theCrag's tags). Fog wets exposed faces. Water dries off hour by hour by " +
                    "Penman's equation with the FAO-56 terms Open-Meteo uses: the light the face absorbs (direct sun at its angle while it " +
                    "reaches the face, sky light the terrain lets in, light off the slopes), heat lost to the sky, the air's dryness at the " +
                    "wall's elevation and the wind reaching it; under a clear night sky it turns into dew. " +
                    "The % is how full of water the face is; above a thin film, water runs down the face within an hour or two. A time after " +
                    "\"dry\" is the first time it should be dry, even if it gets wet again later. Weather: Open-Meteo. Walls, routes and tags: theCrag.",
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { openUrl("https://www.thecrag.com/en/climbing/mexico/el-salto") }) { Text("theCrag ↗") }
            }
        }
    }
}

@Composable
private fun WallRow(w: WallState, offset: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).background(Palette.state(w.state), CircleShape))
        Column(Modifier.weight(1f)) {
            Text(w.wall.name, color = Palette.fg, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val now = mapOf("sun" to "sun now", "terrain" to "terrain shadow", "behind" to "shade", "shade" to "your shade", "night" to "night")[w.now] ?: ""
            Small(
                if (w.deg == null) "aspect ? · ${w.wall.count} routes" else "${w.aspect} · $now · ${w.sunHours} h sun",
                if (w.now == "sun") Palette.sun else Palette.muted,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${if (w.state == "dry" && w.seeping) "seeps" else w.state} ${w.wetPct}%", color = Palette.state(w.state), fontSize = 16.sp)
            Small(if (w.state == "dry") "wetness" else w.dryAt?.let { "dry " + Fmt.time(it, offset, "EEE HH:mm").lowercase() } ?: "not dry in 72 h")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WallsTab(c: Conditions, vm: MainVm, openUrl: (String) -> Unit) {
    var zone by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf("") }
    var from by remember { mutableIntStateOf(13) }
    var to by remember { mutableIntStateOf(19) }
    val shown = if (zone == "") c.walls else c.walls.filter { it.wall.zone == zone }
    val month = Fmt.time(c.updated, c.offset, "M").toInt() - 1
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("all", zone == "") { zone = "" }
                c.walls.map { it.wall.zone }.distinct().forEach { z -> Chip(z, zone == z) { zone = z } }
            }
        }
        item { RouteLogFiles(vm) }
        item { Small("★ pins a wall to the widget. Tap a wall for why it's wet or dry, its terrain horizon, sun through the year, aspect, shade windows and routes. The % is how full of water the face is: 0 % fully dry, 100 % soaking wet.") }
        items(shown, key = { it.wall.id }) { w ->
            val isOpen = open == w.wall.id
            Column(
                Modifier.fillMaxWidth()
                    .border(1.dp, if (isOpen) Palette.accent else Palette.muted.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(12.dp, 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Only the header opens and closes the wall, so taps inside the route editor don't.
                Column(Modifier.fillMaxWidth().clickable { open = if (isOpen) "" else w.wall.id }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (w.fav) "★" else "☆", color = if (w.fav) Palette.sun else Palette.muted, fontSize = 22.sp,
                            modifier = Modifier.clickable { vm.toggleFav(w.wall.id) }.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            WallRow(w, c.offset)
                            Small(w.wall.zone)
                        }
                    }
                    Small(
                        when {
                            w.deg == null -> "sun on face today: unknown, set this wall's aspect"
                            w.sunToday.isEmpty() -> "sun on face today: none" + if (w.terrainToday.isNotEmpty()) " (terrain shadow ${w.terrainText})" else ""
                            else -> "sun on face today: ${w.sunText} (${w.sunHours} h)" + if (w.terrainToday.isNotEmpty()) " · terrain shadow ${w.terrainText}" else ""
                        },
                        Palette.fg,
                    )
                    DayBar(w, c)
                }
                if (isOpen) WallDetail(w, c, vm, month, from, to, { from = it }, { to = it }, openUrl)
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Palette.accent else Palette.muted, fontSize = 13.sp,
        modifier = Modifier.border(1.dp, if (selected) Palette.accent else Palette.muted.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(10.dp, 6.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WallDetail(
    w: WallState, c: Conditions, vm: MainVm, month: Int, from: Int, to: Int,
    setFrom: (Int) -> Unit, setTo: (Int) -> Unit, openUrl: (String) -> Unit,
) {
    val b = w.budget
    SectionTitle("why ${w.wetPct}% wet")
    Small(
        "${w.kind} face, holds up to ${w.cap} mm when soaked and keeps a ${Fmt.num(w.cap * 0.35, 2)} mm film once the rest has run down. Last 72 h here: ${b.rain} mm of rain fell, ${b.face} mm reached the face " +
            "(incl. ${b.runoff} mm runoff from ${w.catchmentHa} ha of slope above), ${b.cond} mm from fog and dew, ${b.drain} mm ran down the face, ${b.evap} mm dried off.",
        Palette.fg,
    )
    Small(
        "Now here: ${Fmt.num(w.tempHere)}° and ${w.rhHere.roundToInt()}% humidity at ${w.terrain?.elev?.roundToInt() ?: "?"} m · " +
            "wind reaching the face ${w.windExpo}% · face sees ${w.sky}% of the sky" +
            (if (w.seeping) " · tufas and cracks likely seeping" else "") +
            (if (w.crTags.isNotEmpty()) " · theCrag: " + w.crTags.joinToString(", ") else ""),
    )
    val src = when (w.aspectSource) {
        "yours" -> "set by you"
        "theCrag" -> "from theCrag"
        "terrain" -> "estimated from the terrain slope, ${w.terrain?.facing?.roundToInt()}°"
        "sun tag" -> "from theCrag's sun tag"
        else -> "theCrag has none and the terrain is too flat here to tell"
    }
    // The charts and the aspect and shade editors fold away; what they say stays in these two lines.
    var sunOpen by rememberSaveable(w.wall.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { sunOpen = !sunOpen }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("sun and shade")
        Spacer(Modifier.weight(1f))
        Small(if (sunOpen) "hide ▴" else "sun path, year, edit ▾", Palette.accent)
    }
    Small("faces ${w.aspect ?: "unknown"} ($src)", Palette.fg)
    Small("Your shade windows (trees, overhangs the terrain model can't see): ${w.shadeText.ifEmpty { "none" }}", Palette.fg)
    if (sunOpen) {
        SectionTitle("horizon from this wall")
        HorizonChart(w, c)
        Small("Grey: terrain seen from the wall's GPS point. Lighter band: directions the sun can hit the face from. Dots: the sun every 20 minutes today, on the longest and on the shortest day; dots inside the grey are hidden by terrain. Ring: the sun now.")
        SectionTitle("sun on the face through the year (15th of each month)")
        SunYearChart(w, month)
        Small("Yellow: sun on the face. Grey: terrain shadow while the sun is in front of the face. Hours of sun per day at the right.")
        SectionTitle("aspect")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (DIRS + "auto").forEach { d ->
                Chip(d, w.aspect == d && w.aspectSource == "yours" || (d == "auto" && w.aspectSource != "yours")) {
                    vm.setAspect(w.wall.id, if (d == "auto") null else d)
                }
            }
        }
        SectionTitle("shade windows")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("from %02d:00".format(from), color = Palette.fg, fontSize = 13.sp, modifier = Modifier.width(84.dp))
            Slider(value = from.toFloat(), onValueChange = { setFrom(it.roundToInt()) }, valueRange = 0f..23f, steps = 22, modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("to %02d:00".format(to), color = Palette.fg, fontSize = 13.sp, modifier = Modifier.width(84.dp))
            Slider(value = to.toFloat(), onValueChange = { setTo(it.roundToInt()) }, valueRange = 1f..24f, steps = 22, modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.addShade(w.wall.id, from, to) }) { Text("add shade %02d–%02d".format(from, to)) }
            OutlinedButton(onClick = { vm.clearShade(w.wall.id) }) { Text("clear") }
        }
    }
    if (w.seeping) Small("Heavy rain recently: tufas and cracks may still seep.", Palette.damp)
    val routes = w.wall.routes
    if (routes.isEmpty()) SectionTitle("routes: none listed on theCrag") else {
        val logged = routes.map { vm.log[it.id] }
        SectionTitle("routes · ${logged.count { it.done }}/${routes.size} done")
        val byAscent = Ascent.entries.reversed().mapNotNull { a ->
            logged.count { it.ascent == a }.takeIf { it > 0 }?.let { "$it ${if (a == Ascent.FALLS) "con vuelos" else a.label}" }
        }
        if (byAscent.isNotEmpty()) Small(byAscent.joinToString(" · "), Palette.fg)
        Small("Tick a route when you've done it; tap it for how it went, notes and a photo. Only on your phone.")
    }
    var openRoute by rememberSaveable(w.wall.id) { mutableStateOf("") }
    routes.take(150).forEach { r ->
        RouteItem(r, vm, openRoute == r.id) { openRoute = if (openRoute == r.id) "" else r.id }
    }
    TextButton(onClick = { openUrl(w.wall.url) }) { Text("open on theCrag ↗") }
}

@Composable
private fun RadarTab(vm: MainVm) {
    LaunchedEffect(Unit) { vm.loadRadar() }
    val r = vm.radar
    val offset = vm.conditions?.offset ?: -21600
    var frame by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    LaunchedEffect(playing, r) {
        while (playing && r != null && r.rainy && r.frames.isNotEmpty()) {
            delay(700)
            frame = (frame + 1) % r.frames.size
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (r == null || r.frames.isEmpty()) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                if (vm.radarLoading) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(); Spacer(Modifier.height(8.dp)); Small("building radar frames…")
                } else Small("radar unavailable · tap refresh")
            }
        } else {
            val f = r.frames[frame % r.frames.size]
            Image(f.bitmap.asImageBitmap(), contentDescription = "rain radar", modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            Small("${Fmt.time(f.time, offset, "HH:mm")} · ${frame % r.frames.size + 1}/${r.frames.size} · ${r.km} km across · red ring: El Salto", Palette.fg)
            if (!r.rainy) Small("No rain on the radar here in the last hour, so every frame looks the same.", Palette.fg)
            LinearProgressIndicator(progress = { (frame % r.frames.size + 1f) / r.frames.size }, modifier = Modifier.fillMaxWidth(), color = Palette.accent)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { playing = !playing }) { Text(if (playing) "pause" else "play") }
                OutlinedButton(onClick = { playing = false; frame = (frame + r.frames.size - 1) % r.frames.size }) { Text("◀") }
                OutlinedButton(onClick = { playing = false; frame = (frame + 1) % r.frames.size }) { Text("▶") }
            }
            Small(
                "Rain: RainViewer radar, the last hour in 10-minute frames (light blue light, dark blue heavy). Clouds: GOES-East GeoColor " +
                    "(NASA GIBS)${r.cloudsTime?.let { ", " + Fmt.time(it, offset, "HH:mm") } ?: ""}. Map: Esri. Mexico's radar coverage has gaps: " +
                    "clouds without blue may still be raining.",
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteItem(r: Route, vm: MainVm, isOpen: Boolean, toggle: () -> Unit) {
    val e = vm.log[r.id]
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = e.done, onCheckedChange = { d -> vm.log.update(r.id) { it.copy(done = d) } },
                modifier = Modifier.size(36.dp),
                colors = CheckboxDefaults.colors(checkedColor = Palette.accent, uncheckedColor = Palette.muted, checkmarkColor = Palette.bg),
            )
            Text(r.grade, color = Palette.rainAhead, fontSize = 13.sp, modifier = Modifier.width(52.dp), maxLines = 1)
            Column(Modifier.weight(1f)) {
                Text(r.name, color = Palette.fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(e.ascentText.ifEmpty { null }, e.note.lineSequence().firstOrNull()?.ifEmpty { null }, if (e.photo) "📷" else null)
                if (sub.isNotEmpty() && !isOpen) Text(sub.joinToString(" · "), color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (r.pitches > 1) "${r.pitches}p" else "", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.width(28.dp))
            Text("★".repeat(r.stars.coerceIn(0, 5)), color = Palette.sun, fontSize = 12.sp, modifier = Modifier.width(52.dp))
        }
        if (isOpen) Column(Modifier.padding(start = 36.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Ascent.entries.forEach { a ->
                    Chip(if (a == Ascent.FALLS) "X vuelos" else a.label, e.ascent == a) {
                        // Topping out counts as done; picking it again clears it.
                        vm.log.update(r.id) { if (it.ascent == a) it.copy(ascent = null) else it.copy(ascent = a, done = it.done || a != Ascent.NO_TOP) }
                    }
                }
            }
            if (e.ascent == Ascent.FALLS) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.log.update(r.id) { it.copy(falls = maxOf(1, it.falls - 1)) } }) { Text("−") }
                Text(e.ascentText, color = Palette.fg, fontSize = 14.sp)
                OutlinedButton(onClick = { vm.log.update(r.id) { it.copy(falls = it.falls + 1) } }) { Text("+") }
            }
            OutlinedTextField(
                value = e.note, onValueChange = { t -> vm.log.update(r.id) { it.copy(note = t) } },
                modifier = Modifier.fillMaxWidth(), placeholder = { Text("notes: beta, gear, conditions…") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.fg),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.accent, unfocusedBorderColor = Palette.muted.copy(alpha = 0.4f)),
            )
            val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.setPhoto(r.id, it) } }
            val photo by produceState<ImageBitmap?>(null, r.id, e.photo, vm.photoVersion) {
                value = if (!e.photo) null else withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(vm.log.photoFile(r.id).path)?.asImageBitmap()
                }
            }
            photo?.let {
                Image(it, contentDescription = "photo of ${r.name}", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(if (e.photo) "change photo" else "add photo")
                }
                if (e.photo) OutlinedButton(onClick = { vm.removePhoto(r.id) }) { Text("remove") }
            }
        }
    }
}

/** Export the route log to a file (to back it up or move phones) and import one back. */
@Composable
private fun RouteLogFiles(vm: MainVm) {
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { vm.exportLog(it) } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importLog(it) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Small("route log", Palette.fg)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { exporter.launch("elsalto-routes-${Fmt.time(System.currentTimeMillis() / 1000, 0, "yyyy-MM-dd")}.json") }) { Text("export") }
        TextButton(onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) { Text("import") }
    }
}
