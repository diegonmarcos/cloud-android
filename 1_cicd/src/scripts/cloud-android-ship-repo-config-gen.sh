#!/bin/sh
# cloud-android-ship-repo-config-gen — expand config.json + per-solution build.json
# into 2_configs/dist/*.json derived artifacts.
# Kept minimal for v1 — pipeline placeholder, mirrors cloud/ pattern.
set -eu
. "$(dirname "$0")/cloud-android-ship-lib.sh"

OUT="$CLOUD_ANDROID_ROOT/2_configs/dist"
mkdir -p "$OUT"

log_step "Generating 2_configs/dist/solutions.json"
solutions_json=$(
    for dir in $(list_solutions); do
        [ -f "$dir/build.json" ] || continue
        name=$(basename "$dir")
        jq --arg id "$name" '. + {id: $id}' "$dir/build.json"
    done | jq -s .
)
echo "$solutions_json" > "$OUT/solutions.json"

log_step "Generating 2_configs/dist/devices.json"
jq '.devices' "$CONFIG_FILE" > "$OUT/devices.json"

log_step "Generating 2_configs/dist/architecture.json"
jq '.architecture' "$CONFIG_FILE" > "$OUT/architecture.json"

log_info "Config generation complete → $OUT"
