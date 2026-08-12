#!/bin/sh
# cloud-android-ship-orchestrate-ship — top-level ship orchestrator
# Dispatches per-solution build.sh (which is a symlink to the container engine).
set -eu
. "$(dirname "$0")/cloud-android-ship-lib.sh"

ARG_ALL=false
ARG_DEVICE=""
ARG_SOLUTION=""

while [ $# -gt 0 ]; do
    case "$1" in
        --all)      ARG_ALL=true;        shift ;;
        --device)   ARG_DEVICE="$2";     shift 2 ;;
        --solution) ARG_SOLUTION="$2";   shift 2 ;;
        -*)         log_error "Unknown flag: $1"; exit 1 ;;
        *)          [ -z "$ARG_SOLUTION" ] && ARG_SOLUTION="$1"; shift ;;
    esac
done

ship_one() {
    dir="$1"
    name=$(basename "$dir")
    log_step "ship → $name"
    if [ -x "$dir/build.sh" ]; then
        ( cd "$dir" && ./build.sh ship )
    else
        log_warn "$name has no build.sh — skipping"
    fi
}

if [ "$ARG_ALL" = "true" ]; then
    for dir in $(list_solutions); do
        ship_one "$dir"
    done
    exit 0
fi

if [ -n "$ARG_SOLUTION" ]; then
    dir=$(resolve_solution_dir "$ARG_SOLUTION") || { log_error "Unknown solution: $ARG_SOLUTION"; exit 1; }
    ship_one "$dir"
    exit 0
fi

log_error "Specify --solution <name>, --device <name>, or --all"
exit 1
