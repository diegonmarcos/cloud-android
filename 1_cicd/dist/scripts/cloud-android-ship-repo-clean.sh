# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-ship-repo-clean.sh ───
#!/bin/sh
# cloud-android-ship-repo-clean — remove all dist/ and build artifacts
set -eu
. "$(dirname "$0")/cloud-android-ship-lib.sh"

log_step "Cleaning dist/"
find "$CLOUD_ANDROID_ROOT" -type d -name 'dist' -not -path '*/node_modules/*' -not -path '*/.git/*' -print -exec rm -rf {} +

log_step "Cleaning gradle/android build/"
find "$SOLUTIONS_DIR" -type d \( -name 'build' -o -name '.gradle' -o -name '.cxx' \) -not -path '*/node_modules/*' -print -exec rm -rf {} + 2>/dev/null || true

log_step "Cleaning nix results"
find "$CLOUD_ANDROID_ROOT" -maxdepth 4 -type l -name 'result' -print -exec rm -f {} + 2>/dev/null || true

log_info "Clean complete."
