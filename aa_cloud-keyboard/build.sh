#!/usr/bin/env bash
# Cloud Keyboard — Build Dispatcher
# Mirrors the superapp engine pattern. All config read from build.json.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="$SCRIPT_DIR/dist"
CMD="${1:-help}"

log()    { printf "[%s] %s\n" "$(date '+%H:%M:%S')" "$1"; }
errlog() { printf "\033[0;31m[%s] ERROR: %s\033[0m\n" "$(date '+%H:%M:%S')" "$1" >&2; }

in_nix() {
  if [ "${BYPASS_NIX:-0}" = "1" ]; then "$@"
  else
    command -v nix >/dev/null 2>&1 || { errlog "nix not on PATH; set BYPASS_NIX=1"; exit 1; }
    nix develop "$SCRIPT_DIR" --command "$@"
  fi
}

_gradle() { in_nix gradle --no-daemon -p "$SCRIPT_DIR" "$@"; }

_resolve_gif_keys() {
  [ -n "${GIPHY_API_KEY:-}" ] && { log "media: pre-set GIPHY_API_KEY from env"; return 0; }
  local vault="${VAULT_DIR:-}"
  [ -z "$vault" ] && return 0
  local sec_rel; sec_rel="$(python3 -c "import json,sys; d=json.load(open('$SCRIPT_DIR/build.json')); print(d.get('keyboard_media',{}).get('vault_secrets',''))" 2>/dev/null)"
  [ -z "$sec_rel" ] && return 0
  local sec="$vault/$sec_rel"
  [ -f "$sec" ] || { log "media: $sec_rel not found; GIF tab will show no-key state"; return 0; }
  export GIPHY_API_KEY; GIPHY_API_KEY="$(SOPS_AGE_KEY="${SOPS_AGE_KEY:-}" sops --config /dev/null -d --extract '["giphy_api_key"]' "$sec" 2>/dev/null || true)"
  log "media: giphy=$([ -n "$GIPHY_API_KEY" ] && echo yes || echo no)"
}

_resolve_signing() {
  local vault="${VAULT_DIR:-}"
  [ -z "$vault" ] && return 0
  local secrets="$vault/A0_keys/providers/android/signing.secrets.yaml"
  [ -f "$secrets" ] || { log "Signing secrets not found; skipping."; return 0; }
  eval "$(SOPS_AGE_KEY="${SOPS_AGE_KEY:-}" sops -d "$secrets" \
    | grep -E '^(ANDROID_KEYSTORE|ANDROID_KEY)' | awk '{print "export " $0}')"
  local ks="$vault/A0_keys/providers/android/release.jks"
  [ -f "$ks" ] && export ANDROID_KEYSTORE_FILE="$ks"
  log "Signing config resolved."
}

case "$CMD" in
  build)
    log "Building Cloud Keyboard APK (debug)…"
    _resolve_gif_keys
    mkdir -p "$DIST_DIR"
    _gradle :app:assembleDebug
    find "$SCRIPT_DIR/app/build/outputs/apk/debug" -name "*.apk" \
      -exec cp {} "$DIST_DIR/Cloud-Keyboard.apk" \;
    log "APK → $DIST_DIR/Cloud-Keyboard.apk"
    ;;
  release)
    log "Building Cloud Keyboard APK (release)…"
    _resolve_gif_keys
    _resolve_signing
    mkdir -p "$DIST_DIR"
    _gradle :app:assembleRelease
    find "$SCRIPT_DIR/app/build/outputs/apk/release" -name "*.apk" \
      -exec cp {} "$DIST_DIR/Cloud-Keyboard.apk" \;
    log "APK → $DIST_DIR/Cloud-Keyboard.apk"
    ;;
  clean)
    _gradle clean
    rm -rf "$DIST_DIR"
    ;;
  oras-push)
    log "Pushing APK to GHCR via ORAS…"
    SHA="${GITHUB_SHA:-$(git -C "$SCRIPT_DIR" rev-parse HEAD)}"
    SHORT="${SHA:0:8}"
    oras push "ghcr.io/diegonmarcos/cloud-keyboard:latest" \
      --media-type "application/vnd.android.package-archive" \
      "$DIST_DIR/Cloud-Keyboard.apk"
    oras push "ghcr.io/diegonmarcos/cloud-keyboard:sha-${SHORT}" \
      --media-type "application/vnd.android.package-archive" \
      "$DIST_DIR/Cloud-Keyboard.apk"
    log "Pushed :latest + :sha-${SHORT}"
    ;;
  gh-release)
    log "Publishing to GitHub Releases (rolling latest)…"
    gh release upload latest "$DIST_DIR/Cloud-Keyboard.apk" --clobber 2>/dev/null \
      || gh release create latest \
           --title "Cloud Keyboard (rolling)" \
           --notes "Auto-updated from main." \
           "$DIST_DIR/Cloud-Keyboard.apk"
    ;;
  help|*)
    echo "Usage: build.sh <build|release|clean|oras-push|gh-release>"
    ;;
esac
