#!/bin/sh
# Put the currently published walls, routes and terrain in seed/, bundled into the APK as the copy
# used offline on first start. Optional: without it the app downloads the data on first start.
#
#   tools/seed.sh [data url]
set -eu
url=${1:-${ELSALTO_DATA_URL:-https://bucho2398.github.io/elsalto-data/}}
url=${url%/}/
dir=$(git rev-parse --show-toplevel)/seed
rm -rf "$dir"; mkdir -p "$dir"
curl -sfL "${url}index.json" -o "$dir/index.json"
for f in walls.json terrain.json; do
  curl -sfL "$url$f" -o "$dir/$f"
  want=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['files'][sys.argv[2]]['sha256'])" "$dir/index.json" "$f")
  have=$(sha256sum "$dir/$f" | cut -d' ' -f1)
  [ "$want" = "$have" ] || { echo "$f doesn't match index.json (published mid-update?): try again" >&2; exit 1; }
done
echo "seeded $(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['version'])" "$dir/index.json") from $url"
