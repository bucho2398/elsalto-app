# El Salto conditions

An Android app and home-screen widget for climbing at **El Salto, Nuevo León**. It answers one
question: which walls are dry, and which will be in the sun or shade?

**[⬇ Download ElSalto.apk](https://github.com/bucho2398/elsalto-app/raw/download/ElSalto.apk)** —
official build 1.0.12, 2026-10-12.

Free software under the [GPL-3.0](LICENSE). This repository is the home of the app: forks are
welcome, and changes come back here through pull requests.

## Install

1. Open the download link above on your Android phone (Android 8 or newer).
2. Open the downloaded `ElSalto.apk`. Android asks once to allow installs from your browser or files
   app; allow it and tap *Install*.
3. For the widget: long-press the home screen → *Widgets* → *El Salto conditions*.

New versions install over the old one and keep your favourites. This page always has the latest
build. Only the APK linked here is the official app, signed by its maintainer.

## What it shows

- **Conditions:** rain over the last and next 72 hours, temperature, humidity, wind, sunrise and
  sunset, and a 7-day outlook ([Open-Meteo](https://open-meteo.com)).
- **Walls:** every zone, wall and route at El Salto from [theCrag](https://www.thecrag.com/en/climbing/mexico/el-salto),
  each wall with its estimated rock wetness (0 % dry to 100 % soaking) and why: the rain at its own
  spot, how much reached the face, runoff from the slope above, fog and dew, wind and sun.
- **Sun and shade from the terrain:** each wall's GPS point plus SRTM elevation data gives a 360°
  horizon, so the app knows when ridges or the canyon's other side shade it: a sun bar for today, a
  horizon chart with the sun's path, and a sun calendar for the year.
- **Radar:** the last hour of RainViewer rain radar over GOES-East satellite clouds.
- **Your walls:** star favourites (shown on the widget), set a wall's direction, and add shade windows
  for trees or roofs the terrain data can't see.
- **Your routes:** tick the routes you've done and how (sin top, top, X vuelos, encadenada), add
  notes and a photo; each wall counts how many of its routes you've done. All of this stays on your phone.

Wall and route data comes with each new version. Wetness is an estimate: check the rock.

## Build it yourself

You need JDK 17, the Android SDK (platform 34) and Gradle 8.9:

```sh
gradle :app:testReleaseUnitTest :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Every push and pull request is also built by
[`ci.yml`](.github/workflows/ci.yml); the APK is attached to the run as an artifact.

Builds from source are called **El Salto FOSS** and use the application id
`io.github.bucho2398.elsalto.foss`, so they install next to the official app without replacing it
or touching its data. Only the maintainer's release pipeline builds the official id.

### Code map

| part | where |
|---|---|
| Model: sun position, terrain shading, wetness | `app/src/main/java/.../Model.kt` |
| Weather (Open-Meteo), radar (RainViewer, NASA GIBS, Esri) | `Weather.kt`, `Radar.kt` |
| Screens, charts, widget, route log | `Screens.kt`, `Charts.kt`, `Widget.kt`, `Log.kt` |
| Walls, routes and tags from theCrag | `data/crag.json` (snapshot, see [DATA.md](DATA.md)) |
| Terrain: horizons, facing, catchments | `data/terrain.json`, made by `tools/terrain.py` (SRTM via AWS Terrain Tiles) |
| Model test | `ModelTest.kt` checks the Kotlin model against `app/src/test/resources/expected.json` |

### The wetness model, briefly

Hour by hour over the last 72 h and the next 72 h, with the weather at each wall's own location and
elevation:

- **Rain on the face:** from above (slabs only), as wind-driven rain (ISO 15927-3, using the wind
  direction against the face and how sheltered the canyon is upwind), and as runoff from the
  catchment above.
- **Wall shape:** overhangs, roofs, caves and walls theCrag tags "Dry in the rain" catch less.
- **Fog and dew:** fog wets exposed faces; dew is what the drying equation gives when a face
  radiates below the dew point under a clear night sky.
- **Drying:** Penman's equation for a wet surface, with FAO-56 terms as Open-Meteo computes its
  hourly ET₀, a facade convection coefficient (5.7 + 3.8 u), and the light the face absorbs: direct
  sun at its angle of incidence while it clears the terrain horizon, diffuse sky light less the
  terrain, and light reflected off the slopes.
- **Sun position:** a port of [mourner/suncalc](https://github.com/mourner/suncalc).

A face holds up to 3 mm of water (slab), 1.5 mm (vertical), 1 mm (overhang) or 0.8 mm (roof); the %
is how full it is. It keeps a film of 35 % of that: the rest runs down the face, 60 % of it each hour
(30 % off a slab). The dry time shown is the first hour it should be dry.

## Forks and contributions

- Pull requests and issues are welcome here. See [CONTRIBUTING.md](CONTRIBUTING.md).
- If you publish your own APKs, give them your own application id and name (in
  `app/build.gradle.kts`) and your own signing key, so nobody mistakes them for the official app.
- Please don't put theCrag scraping on a schedule: the data here is a snapshot, refreshed by hand
  from time to time. See [DATA.md](DATA.md).

## Feedback

You can use 2 built-in features for reports and feedback:
I'll fix and push update ASAP.

| Feature | Purpose |
| --- | --- |
| [GitHub Issues](https://github.com/bucho2398/elsalto-app/issues) | Report bugs, request features, suggest improvements |
| [GitHub Discussions](https://github.com/bucho2398/elsalto-app/discussions) | Share ideas, ask questions, give feedback |

## Licenses

- Code: GPL-3.0, see [LICENSE](LICENSE).
- Sun-position code ported from suncalc, © Volodymyr Agafonkin, BSD 2-Clause ([LICENSE-suncalc](LICENSE-suncalc)).
- Data in `data/` is not covered by the GPL. See [DATA.md](DATA.md).
- Weather from Open-Meteo (CC BY 4.0); radar and imagery from RainViewer, NASA GIBS and Esri at run time.
