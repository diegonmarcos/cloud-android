# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-ship-orchestrate-device.sh ───
#!/bin/sh
# cloud-android-ship-orchestrate-device — ship all solutions targeting one device
set -eu
. "$(dirname "$0")/cloud-android-ship-lib.sh"

device="${1:-}"
[ -z "$device" ] && { log_error "usage: build.sh ship-device <device>"; exit 1; }

type=$(device_field "$device" type) || true
[ -z "$type" ] && { log_error "Unknown device: $device"; exit 1; }

log_step "ship to device: $device ($type)"

for dir in $(list_solutions); do
    target=$(jq -r ".deploy.device // empty" "$dir/build.json" 2>/dev/null)
    [ "$target" = "$device" ] || continue
    name=$(basename "$dir")
    log_step "ship → $name → $device"
    ( cd "$dir" && ./build.sh ship )
done
