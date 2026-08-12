#!/bin/sh
# Alias wrapper so a_solutions/<dir>/build.sh symlink name is neutral.
# The real engine is cloud-android-build-apk-engine.sh (handles apk/native/library).
exec sh "$(dirname "$0")/cloud-android-build-apk-engine.sh" "$@"
