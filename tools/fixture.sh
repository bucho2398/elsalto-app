#!/bin/sh
# Download the walls and terrain that ModelTest's expected.json was made from, to build/fixture.
# Pinned to one elsalto-data commit, so editing the live data never breaks the test. When the model
# or the fixture changes, update COMMIT and regenerate expected.json (tools/expected.py).
set -eu
COMMIT=50bc5345e378232a8a3a311e51560d272ecc1d69
dir=$(git rev-parse --show-toplevel)/build/fixture
mkdir -p "$dir"
for f in walls.json terrain.json; do
  curl -sfL "https://raw.githubusercontent.com/bucho2398/elsalto-data/$COMMIT/$f" -o "$dir/$f"
done
echo "fixture: elsalto-data@$(echo $COMMIT | cut -c1-7) in $dir"
