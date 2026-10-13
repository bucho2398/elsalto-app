#!/usr/bin/env python3
"""Make app/src/test/resources/expected.json with the reference model (the omalauncher widget's
elsalto-conditions), from the site.json and walls.json fixtures, the walls and terrain tools/fixture.sh
downloads to build/fixture, and no preferences, at the
fixtures' moment, plus each wall's wetness at LATER, after the fixture's forecast rain. ModelTest checks the app's Kotlin model against it.

    tools/fixture.sh
    python3 tools/expected.py ~/.local/bin/elsalto-conditions > app/src/test/resources/expected.json
"""
import contextlib, importlib.machinery, importlib.util, io, json, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/test/resources")
NOW = 1791765000
LATER = 1791946800   # Tue 13 Oct 21:00, after 3.3 mm of rain

loader = importlib.machinery.SourceFileLoader("model", sys.argv[1])
spec = importlib.util.spec_from_loader("model", loader)
m = importlib.util.module_from_spec(spec)
loader.exec_module(m)

m.CRAG = os.path.join(ROOT, "build/fixture/walls.json")
m.TERRAIN = os.path.join(ROOT, "build/fixture/terrain.json")
m.PREFS = "/nonexistent"
m.update_data = m.reterrain = lambda *a: None
site = json.load(open(os.path.join(RES, "site.json")))
points = json.load(open(os.path.join(RES, "walls.json")))
m.weather = lambda *a: site
m.wall_weather = lambda pts: points if len(pts) == len(points) else sys.exit(f"{len(pts)} weather points, fixture has {len(points)}")


def run(now):
    m.time.time = lambda: now
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        m.main()
    return json.loads(out.getvalue())


c = run(NOW)
later = run(LATER)
r = c["rain"]
print(json.dumps({
    "now": NOW,
    "rainPast": [r["past24"], r["past48"], r["past72"]],
    "rainNext": [r["next24"], r["next48"], r["next72"]],
    "walls": {w["id"]: {"wetPct": w["wetPct"], "state": w["state"], "sunHours": w["sunHours"], "aspect": w["aspect"],
                        "windExpo": w["windExpo"], "sky": w["sky"], "now": w["now"], "dryAt": w["dryAt"],
                        "terrainHours": w["terrainHours"], "yearHours": [x["h"] for x in w["year"]]}
              for w in c["walls"]},
    "later": {"now": LATER, "walls": {w["id"]: {"wetPct": w["wetPct"], "state": w["state"], "dryAt": w["dryAt"]} for w in later["walls"]}},
}, ensure_ascii=False, indent=1))
