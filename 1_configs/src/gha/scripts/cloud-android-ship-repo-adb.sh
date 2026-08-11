#!/bin/sh
# cloud-android-ship-repo-adb — ADB shell into a declared device
set -eu
. "$(dirname "$0")/cloud-android-ship-lib.sh"

device="${1:-}"
[ -z "$device" ] && {
    log_info "Devices:"
    list_devices | sed 's/^/  /'
    exit 0
}

serial=$(device_field "$device" adb_serial)
type=$(device_field "$device" type)
[ -z "$type" ] && { log_error "Unknown device: $device"; exit 1; }

case "$type" in
    waydroid|hardware)
        [ -z "$serial" ] && { log_error "device $device has no adb_serial in config.json"; exit 1; }
        shift
        if [ $# -eq 0 ]; then
            exec adb -s "$serial" shell
        else
            exec adb -s "$serial" "$@"
        fi
        ;;
    termux)
        host=$(device_field "$device" host)
        port=$(device_field "$device" ssh_port)
        shift 2>/dev/null || true
        exec ssh -p "$port" "$host"
        ;;
    *)
        log_error "Unsupported device type: $type"; exit 1 ;;
esac
