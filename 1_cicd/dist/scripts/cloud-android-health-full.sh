# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-health-full.sh ───
#!/bin/sh
# cloud-android-health-full — L1-L3 health probes across declared devices
set -eu
. "$(dirname "$0")/cloud-android-ship-lib.sh"

FAIL=0
check() {
    name="$1"; shift
    if "$@" >/dev/null 2>&1; then
        printf '  ✓ %s\n' "$name"
    else
        printf '  ✗ %s\n' "$name"
        FAIL=$((FAIL + 1))
    fi
}

for dev in $(list_devices); do
    type=$(device_field "$dev" type)
    log_step "L1 reach — $dev ($type)"
    case "$type" in
        waydroid|hardware)
            serial=$(device_field "$dev" adb_serial)
            [ -z "$serial" ] && { printf '  ✗ no adb_serial\n'; FAIL=$((FAIL + 1)); continue; }
            check "adb devices" sh -c "adb devices | grep -w '$serial'"
            check "device up"   adb -s "$serial" shell echo ok
            ;;
        termux)
            host=$(device_field "$dev" host)
            port=$(device_field "$dev" ssh_port)
            check "ssh reach" ssh -o BatchMode=yes -o ConnectTimeout=3 -p "$port" "$host" echo ok
            ;;
    esac
done

log_step "summary: $FAIL failure(s)"
exit $FAIL
