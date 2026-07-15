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

_bj() { python3 -c "import json,sys;print(json.load(open('$SCRIPT_DIR/build.json'))$1)" 2>/dev/null; }

# ONE shared Cloud-constellation signing key — mirrors ea_cloud-superapp.
# NO fallback: if the shared key can't be resolved the build FAILS LOUD
# instead of silently signing with the ephemeral android debug keystore
# (a fresh random key per CI run → INSTALL_FAILED_UPDATE_INCOMPATIBLE).
_resolve_signing() {
  # CI secret delivery: trust a pre-set on-disk keystore + alias inside CI only.
  if [ -n "${GITHUB_ACTIONS:-}${CI:-}" ] \
     && [ -n "${ANDROID_KEYSTORE_FILE:-}" ] && [ -f "${ANDROID_KEYSTORE_FILE}" ] && [ -n "${ANDROID_KEY_ALIAS:-}" ]; then
    log "signing: using pre-set ANDROID_KEYSTORE_* (CI secret delivery)"; return 0
  fi
  local ks_rel sec_rel vault ks store_pw key_pw alias_
  ks_rel="$(_bj "['signing']['vault_keystore']")"
  sec_rel="$(_bj "['signing']['vault_secrets']")"
  if [ -z "$ks_rel" ] || [ -z "$sec_rel" ]; then
    errlog "FATAL signing: build.json::signing.vault_keystore/.vault_secrets are empty."
    errlog "  ALL constellation apps MUST sign with the ONE shared key (vault/A0_keys/providers/android/release.jks)."
    exit 1
  fi
  vault="${VAULT_DIR:-$HOME/git/vault}"
  ks="$vault/$ks_rel"
  [ -f "$ks" ] || { errlog "FATAL signing: shared keystore missing at $ks (check out vault / set VAULT_DIR). No fallback."; exit 1; }
  command -v sops >/dev/null 2>&1 || { errlog "FATAL signing: sops not on PATH; cannot decrypt the shared key. Refusing to build."; exit 1; }
  store_pw="$(sops --config /dev/null -d --extract '["keystore_password"]' "$vault/$sec_rel" 2>/dev/null || true)"
  key_pw="$(sops --config /dev/null -d --extract '["key_password"]' "$vault/$sec_rel" 2>/dev/null || true)"
  alias_="$(sops --config /dev/null -d --extract '["key_alias"]' "$vault/$sec_rel" 2>/dev/null || true)"
  if [ -z "$store_pw" ] || [ -z "$alias_" ]; then
    errlog "FATAL signing: cannot decrypt $sec_rel (need SOPS_AGE_KEY). Refusing to fall back to any other key."
    exit 1
  fi
  export ANDROID_KEYSTORE_FILE="$ks"
  export ANDROID_KEYSTORE_PASSWORD="$store_pw"
  export ANDROID_KEY_PASSWORD="$key_pw"
  export ANDROID_KEY_ALIAS="$alias_"
  log "signing: ONE shared constellation key (alias $alias_) from vault/$ks_rel"
}

# Re-sign the built APK with the shared key + verify — the gate that makes a
# random/debug-signed APK impossible to publish (even an assembleDebug output
# gets overwritten with the constellation signature).
_enforce_signature() {
  local apk="$1" bt zipalign apksigner
  [ -f "$apk" ] || { errlog "sign-enforce: missing APK $apk"; exit 1; }
  _resolve_signing
  bt="$(ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/nonexistent}}"/build-tools/* 2>/dev/null | sort -V | tail -1)"
  zipalign="$bt/zipalign"; apksigner="$bt/apksigner"
  [ -x "$apksigner" ] || { errlog "sign-enforce: apksigner missing (bt=$bt)"; exit 1; }
  "$zipalign" -f -p 4 "$apk" "${apk}.aln" 2>/dev/null && mv -f "${apk}.aln" "$apk" || rm -f "${apk}.aln"
  "$apksigner" sign --ks "$ANDROID_KEYSTORE_FILE" --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD" \
    --ks-key-alias "$ANDROID_KEY_ALIAS" --key-pass "pass:${ANDROID_KEY_PASSWORD:-$ANDROID_KEYSTORE_PASSWORD}" \
    "$apk" || { errlog "sign-enforce: re-sign with shared key failed for $apk"; exit 1; }
  rm -f "${apk}.idsig"
  "$apksigner" verify "$apk" >/dev/null 2>&1 \
    || { errlog "sign-enforce: FATAL $(basename "$apk") not validly signed after shared-key re-sign - refusing"; exit 1; }
  log "sign-enforce: OK $(basename "$apk") signed by the ONE shared constellation key"
}

case "$CMD" in
  build)
    log "Building Cloud Keyboard APK (debug)…"
    _resolve_gif_keys
    mkdir -p "$DIST_DIR"
    _gradle :app:assembleDebug
    find "$SCRIPT_DIR/app/build/outputs/apk/debug" -name "*.apk" \
      -exec cp {} "$DIST_DIR/Cloud-Keyboard.apk" \;
    _enforce_signature "$DIST_DIR/Cloud-Keyboard.apk"
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
    _enforce_signature "$DIST_DIR/Cloud-Keyboard.apk"
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
    # ORAS 1.x dropped --media-type; per-file media type is set via `file:type`.
    reg="$(python3 -c "import json;d=json.load(open('$SCRIPT_DIR/build.json'))['release']['ghcr'];print(f\"{d['registry']}/{d['namespace']}/{d['image']}\")")"
    mt="$(python3 -c "import json;print(json.load(open('$SCRIPT_DIR/build.json'))['release']['ghcr']['media_type'])")"
    ( cd "$DIST_DIR" && oras push "${reg}:latest"          "Cloud-Keyboard.apk:${mt}" )
    ( cd "$DIST_DIR" && oras push "${reg}:sha-${SHORT}"    "Cloud-Keyboard.apk:${mt}" )
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
