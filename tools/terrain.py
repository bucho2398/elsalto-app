#!/usr/bin/env python3
"""Terrain around El Salto's walls, for the climbing widget.

From SRTM elevation (AWS Terrain Tiles, Terrarium encoding, zoom 13 ~ 17 m/px,
30 m source) it works out for each wall from theCrag (~/.cache/elsalto/crag.json):
  - its ground elevation, slope and the direction the slope faces (used when
    theCrag has no aspect for the wall),
  - its horizon: for every 2° of azimuth, the highest angle the surrounding
    terrain (the opposite canyon wall, ridges, the cliff itself) rises to, out to 8 km.
  - its catchment: the hillside area (D8 flow accumulation) that drains over
    the top of the wall, feeding runoff in rain and seepage for days after.
The sun is on a wall only when it is above that horizon. Writes data/terrain.json (or $TERRAIN_OUT)."""
import io, json, math, os, subprocess, sys, time
import numpy as np
from PIL import Image

HERE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data")
CRAG = os.environ.get("CRAG_OUT", os.path.join(HERE, "crag.json"))
OUT = os.environ.get("TERRAIN_OUT", os.path.join(HERE, "terrain.json"))
TILES = os.environ.get("DEM_TILES", os.path.expanduser("~/.cache/elsalto/dem"))
Z = 13
REACH = 8000          # m of terrain that can cast a shadow
STEP_AZ = 2           # degrees between horizon samples
EYE = 2.0             # m above the ground at the wall's GPS point
R_EARTH = 6371000.0


def tile(x, y):
    path = os.path.join(TILES, f"{Z}-{x}-{y}.png")
    if not os.path.exists(path):
        os.makedirs(TILES, exist_ok=True)
        r = subprocess.run(["curl", "-sf", "--max-time", "30",
                            f"https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{Z}/{x}/{y}.png"], capture_output=True)
        if r.returncode != 0:
            raise SystemExit(f"elevation tile {Z}/{x}/{y} unavailable")
        with open(path + ".tmp", "wb") as f:
            f.write(r.stdout)
        os.replace(path + ".tmp", path)
    rgb = np.asarray(Image.open(path).convert("RGB")).astype(np.float64)
    return rgb[..., 0] * 256 + rgb[..., 1] + rgb[..., 2] / 256 - 32768


def world(lat, lon):
    n = 2 ** Z * 256
    return (lon + 180) / 360 * n, (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n


class Dem:
    def __init__(self, lat0, lat1, lon0, lon1):
        x0, y1 = world(lat0, lon0)
        x1, y0 = world(lat1, lon1)
        self.tx0, self.ty0 = int(x0 // 256), int(y0 // 256)
        tx1, ty1 = int(x1 // 256), int(y1 // 256)
        rows = [np.hstack([tile(tx, ty) for tx in range(self.tx0, tx1 + 1)]) for ty in range(self.ty0, ty1 + 1)]
        self.e = np.vstack(rows)
        # Metres per pixel at this latitude.
        self.mpp = 40075016.686 * math.cos(math.radians((lat0 + lat1) / 2)) / (2 ** Z * 256)

    def px(self, lat, lon):
        x, y = world(lat, lon)
        return x - self.tx0 * 256, y - self.ty0 * 256

    def at(self, x, y):
        """Bilinear elevation at pixel coordinates (arrays allowed)."""
        h, w = self.e.shape
        x = np.clip(x, 0, w - 1.001)
        y = np.clip(y, 0, h - 1.001)
        x0, y0 = np.floor(x).astype(int), np.floor(y).astype(int)
        fx, fy = x - x0, y - y0
        e = self.e
        return (e[y0, x0] * (1 - fx) * (1 - fy) + e[y0, x0 + 1] * fx * (1 - fy)
                + e[y0 + 1, x0] * (1 - fx) * fy + e[y0 + 1, x0 + 1] * fx * fy)


def slope_aspect(dem, x, y):
    """Plane fitted to a 5x5 window (~85 m): slope in degrees, and the compass bearing it faces."""
    ox, oy = np.meshgrid(np.arange(-2, 3), np.arange(-2, 3))
    z = dem.at(x + ox, y + oy).ravel()
    A = np.c_[ox.ravel() * dem.mpp, oy.ravel() * dem.mpp, np.ones(25)]
    (gx, gy, _), *_ = np.linalg.lstsq(A, z, rcond=None)
    slope = math.degrees(math.atan(math.hypot(gx, gy)))
    # Downhill is -gradient; pixel y grows southwards.
    facing = math.degrees(math.atan2(-gx, gy)) % 360
    return slope, facing


def horizon(dem, x, y):
    eye = float(dem.at(np.array([x]), np.array([y]))[0]) + EYE
    dists = [30.0]
    while dists[-1] < REACH:
        dists.append(dists[-1] * 1.04 + 8)
    d = np.array(dists)
    out = []
    for az in range(0, 360, STEP_AZ):
        a = math.radians(az)
        xs = x + math.sin(a) * d / dem.mpp
        ys = y - math.cos(a) * d / dem.mpp
        rise = dem.at(xs, ys) - eye - d * d / (2 * R_EARTH) * 0.87   # earth curvature, with refraction
        out.append(round(max(0.0, math.degrees(float(np.max(np.arctan2(rise, d))))), 1))
    return eye - EYE, out


def flow_accumulation(e):
    """D8: each cell drains to its steepest lower neighbour; returns how many cells drain through each cell."""
    h, w = e.shape
    pad = np.pad(e, 1, mode="edge")
    best = np.zeros_like(e)
    to = np.full(e.shape, -1, dtype=np.int64)
    idx = np.arange(h * w).reshape(h, w)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dy == 0 and dx == 0:
                continue
            drop = (e - pad[1 + dy:1 + dy + h, 1 + dx:1 + dx + w]) / math.hypot(dx, dy)
            ny, nx = np.clip(np.arange(h)[:, None] + dy, 0, h - 1), np.clip(np.arange(w)[None, :] + dx, 0, w - 1)
            better = drop > best
            best = np.where(better, drop, best)
            to = np.where(better, idx[ny, nx], to)
    acc = np.ones(h * w)
    flat_to = to.ravel()
    for i in np.argsort(-e.ravel(), kind="stable"):
        j = flat_to[i]
        if j >= 0:
            acc[j] += acc[i]
    return acc.reshape(h, w)


def main():
    crag = json.load(open(CRAG))
    walls = [w for w in crag["walls"] if w.get("pos")]
    lats = [w["pos"][0] for w in walls]
    lons = [w["pos"][1] for w in walls]
    pad_lat = REACH / 111000 + 0.01
    pad_lon = pad_lat / math.cos(math.radians(sum(lats) / len(lats)))
    dem = Dem(min(lats) - pad_lat, max(lats) + pad_lat, min(lons) - pad_lon, max(lons) + pad_lon)
    out = {"source": "SRTM via AWS Terrain Tiles (Terrarium), zoom 13", "step": STEP_AZ, "made": int(time.time()), "walls": {}}
    # Drainage over the walls' area plus 1.5 km, so water from the slopes above each wall is counted.
    x0, y0 = dem.px(max(lats) + 0.015, min(lons) - 0.017)
    x1, y1 = dem.px(min(lats) - 0.015, max(lons) + 0.017)
    x0, y0, x1, y1 = int(x0), int(y0), int(x1) + 1, int(y1) + 1
    acc = flow_accumulation(dem.e[y0:y1, x0:x1])
    cell = dem.mpp ** 2
    for w in walls:
        x, y = dem.px(*w["pos"])
        elev, hz = horizon(dem, x, y)
        # GPS pins usually mark the base of the wall; the cliff is the steepest ground within ~70 m.
        best = (-1, 0, 0, 0)
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                if dx * dx + dy * dy <= 16:
                    sl, fa = slope_aspect(dem, x + dx, y + dy)
                    if sl > best[0]:
                        best = (sl, fa, dx, dy)
        slope, facing = best[0], best[1]
        # Water reaching the top of the wall: the largest drainage among cells up to ~60 m away and above the pin.
        catch = 0.0
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                cx, cy = int(round(x)) + dx - x0, int(round(y)) + dy - y0
                if dx * dx + dy * dy <= 16 and 0 <= cy < acc.shape[0] and 0 <= cx < acc.shape[1] and dem.e[cy + y0, cx + x0] > elev + 5:
                    catch = max(catch, acc[cy, cx] * cell)
        # Sky view of the ground there (the share of the whole sky not hidden by terrain).
        svf = sum(math.cos(math.radians(a)) ** 2 for a in hz) / len(hz)
        out["walls"][w["id"]] = {"pos": w["pos"], "elev": round(elev), "slope": round(slope), "facing": round(facing),
                                 "reliable": slope >= 35, "catchment": round(catch), "svf": round(svf, 2), "horizon": hz}
        print(f"{w['name']:28s} {elev:5.0f} m  slope {slope:3.0f}°  faces {facing:3.0f}°  horizon max {max(hz):4.1f}°  catchment {catch / 1e4:6.2f} ha  sky {svf:.2f}", file=sys.stderr)
    with open(OUT + ".tmp", "w") as f:
        json.dump(out, f, separators=(",", ":"))
    os.replace(OUT + ".tmp", OUT)


if __name__ == "__main__":
    main()
