# Data

The files in `data/` are not covered by the GPL.

## `data/crag.json`: theCrag

Zones, walls and routes at El Salto: names, grades, stars, pitches, GPS positions, aspect, tags, and
yes/no wall features (slab, overhang, roof, seep, …) read from the wall descriptions. No
descriptions, topos, photos or comments are copied.

The source is [theCrag](https://www.thecrag.com/en/climbing/mexico/el-salto), © theCrag
contributors. theCrag shares climber-contributed content under
[Creative Commons Attribution-NonCommercial-ShareAlike](https://creativecommons.org/licenses/by-nc-sa/3.0/);
see theCrag's [copyright notes](https://www.thecrag.com/en/article/mixedcopyright). Use this file
under those terms: credit theCrag, don't use it commercially, and share changes alike.

The file is a snapshot, refreshed by hand by the maintainer. The crawler that makes it isn't
published here. Please don't scrape theCrag on a schedule; if you need fresher data, ask theCrag.

## `data/terrain.json`: terrain

Horizons, facing and catchments computed by `tools/terrain.py` from the
[AWS Terrain Tiles](https://registry.opendata.aws/terrain-tiles/) (SRTM and other public-domain or
openly licensed elevation sources, see their attribution notes) and the wall positions above.
