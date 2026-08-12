#!/bin/sh
# cloud-android-ship-repo-secrets — sops pass-through (show/edit/encrypt/decrypt)
set -eu
. "$(dirname "$0")/cloud-android-ship-lib.sh"

solution="${1:-}"
action="${2:-show}"
[ -z "$solution" ] && { log_error "usage: build.sh secrets <solution> [show|edit|encrypt|decrypt]"; exit 1; }

dir=$(resolve_solution_dir "$solution") || { log_error "Unknown solution: $solution"; exit 1; }
file="$dir/src/secrets.yaml"
[ -f "$file" ] || { log_error "No secrets file at $file"; exit 1; }

case "$action" in
    show)    sops -d "$file" | sed 's/^/  /' ;;
    edit)    sops "$file" ;;
    encrypt) sops -e -i "$file" ;;
    decrypt) sops -d -i "$file" ;;
    *)       log_error "Unknown action: $action"; exit 1 ;;
esac
