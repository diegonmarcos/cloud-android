#!/bin/sh
# cloud-android-ship-orchestrate-build — build one or all solutions to dist/
set -eu
. "$(dirname "$0")/cloud-android-ship-lib.sh"

build_one() {
    dir="$1"
    name=$(basename "$dir")
    log_step "build → $name"
    if [ -x "$dir/build.sh" ]; then
        ( cd "$dir" && ./build.sh build )
    else
        log_warn "$name has no build.sh — skipping"
    fi
}

if [ -z "${1:-}" ]; then
    for dir in $(list_solutions); do
        build_one "$dir"
    done
else
    dir=$(resolve_solution_dir "$1") || { log_error "Unknown solution: $1"; exit 1; }
    build_one "$dir"
fi
