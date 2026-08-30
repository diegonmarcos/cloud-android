#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-build-apk-engine                                   ║
# ║                                                                  ║
# ║ Per-solution build engine. Symlinked as build.sh by each         ║
# ║ a_solutions/<dir>/build.sh. Reads build.json for type + deploy   ║
# ║ and dispatches to gradle / nix / adb-install steps.              ║
# ╚══════════════════════════════════════════════════════════════════╝
set -eu

SOLUTION_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SOLUTION_DIR/../.." && pwd)"
. "$REPO_ROOT/1_cicd/src/scripts/cloud-android-ship-lib.sh"

BUILD_JSON="$SOLUTION_DIR/build.json"
[ -f "$BUILD_JSON" ] || { log_error "Missing $BUILD_JSON"; exit 1; }

NAME=$(jq -r '.name' "$BUILD_JSON")
TYPE=$(jq -r '.type // "apk"' "$BUILD_JSON")
DIST="$SOLUTION_DIR/dist"
SRC="$SOLUTION_DIR/src"

cmd="${1:-all}"; shift 2>/dev/null || true

step_build() {
    mkdir -p "$DIST"
    case "$TYPE" in
        apk)
            log_step "gradle build — $NAME"
            if [ -f "$SRC/gradlew" ]; then
                ( cd "$SRC" && ./gradlew :app:assembleRelease )
                find "$SRC" -name '*.apk' -newer "$BUILD_JSON" -exec cp {} "$DIST/" \;
            elif [ -f "$SRC/flake.nix" ]; then
                ( cd "$SRC" && nix build --no-link --print-out-paths ) > "$DIST/.result"
                outpath=$(cat "$DIST/.result")
                cp -r "$outpath"/* "$DIST/" 2>/dev/null || true
            else
                log_warn "No gradlew or flake.nix — nothing to build"
            fi
            ;;
        native)
            log_step "native build — $NAME"
            if [ -f "$SRC/flake.nix" ]; then
                ( cd "$SRC" && nix build --no-link --print-out-paths ) > "$DIST/.result"
            else
                log_warn "No flake.nix for native build"
            fi
            ;;
        library)
            log_step "library build — $NAME"
            if [ -f "$SRC/gradlew" ]; then
                ( cd "$SRC" && ./gradlew :library:assembleRelease )
            fi
            ;;
        *)
            log_warn "Unknown build type: $TYPE — skipping build"
            ;;
    esac
}

step_secrets() {
    secrets_file="$SRC/secrets.yaml"
    [ -f "$secrets_file" ] || return 0
    log_step "secrets decrypt — $NAME"
    mkdir -p "$DIST"
    sops -d "$secrets_file" > "$DIST/.secrets"
    chmod 600 "$DIST/.secrets"
}

step_sign() {
    [ "$TYPE" = "apk" ] || return 0
    keystore=$(jq -r '.sign.keystore // empty' "$BUILD_JSON")
    [ -n "$keystore" ] || return 0
    log_step "apk sign — $NAME"
    for apk in "$DIST"/*.apk; do
        [ -e "$apk" ] || continue
        apksigner sign --ks "$keystore" "$apk"
    done
}

step_deploy() {
    device=$(jq -r '.deploy.device // empty' "$BUILD_JSON")
    method=$(jq -r '.deploy.method // "adb-install"' "$BUILD_JSON")
    [ -z "$device" ] && { log_warn "no deploy.device declared — skipping"; return 0; }
    serial=$(device_field "$device" adb_serial)
    [ -z "$serial" ] && { log_error "device $device not in config.json"; return 1; }

    case "$method" in
        adb-install)
            log_step "adb install — $NAME → $device"
            for apk in "$DIST"/*.apk; do
                [ -e "$apk" ] || continue
                adb -s "$serial" install -r "$apk"
            done
            ;;
        adb-push)
            remote=$(jq -r '.deploy.remote_path' "$BUILD_JSON")
            adb -s "$serial" push "$DIST"/* "$remote"
            ;;
        *)
            log_error "Unknown deploy method: $method"; return 1 ;;
    esac
}

case "$cmd" in
    build)    step_build ;;
    secrets)  step_secrets ;;
    sign)     step_sign ;;
    deploy)   step_deploy ;;
    all)      step_build; step_secrets; step_sign ;;
    ship)     step_build; step_secrets; step_sign; step_deploy ;;
    clean)    rm -rf "$DIST"; log_info "cleaned $DIST" ;;
    *)        log_error "unknown command: $cmd"; exit 1 ;;
esac
