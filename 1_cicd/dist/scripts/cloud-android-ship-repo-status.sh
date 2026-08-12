# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-ship-repo-status.sh ───
#!/bin/sh
# cloud-android-ship-repo-status — list installed packages on a device
set -eu
. "$(dirname "$0")/cloud-android-ship-lib.sh"

device="${1:-}"
[ -z "$device" ] && { log_error "usage: build.sh status <device>"; exit 1; }

serial=$(device_field "$device" adb_serial)
[ -z "$serial" ] && { log_error "Unknown device: $device"; exit 1; }

log_step "packages on $device ($serial)"
adb -s "$serial" shell pm list packages -3 | sort
