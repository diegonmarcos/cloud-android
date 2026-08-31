#!/usr/bin/env bash
# Cloud Keyboard Libs — Build Dispatcher
# Mirrors the keyboard engine pattern. All config read from build.json.
set -euo pipefail

# Repo identity for GHCR package linkage. GHCR binds a package to whichever repo
# first pushed it, and a workflow's GITHUB_TOKEN only grants packages bound to
# its OWN repo — after the 2026-08 android split every push was denied
# write_package because the packages were still linked to cloud-infra-desktop. This is
# the annotation GHCR reads to (re)link a package, so the link follows whichever
# repo actually ships it. Never hardcoded: CI supplies GITHUB_REPOSITORY, local
# runs fall back to the origin remote.
# A brand-new GHCR package is created PRIVATE by GitHub regardless of the repo,
# so the first push of any new artifact 401s for every unauthenticated consumer
# (the constellation store included). image.source LINKS the package to the repo
# but does NOT make it inherit visibility, and there is no REST endpoint to flip
# it for a USER-owned package (PATCH /user/packages/... returns 404) - it is a
# one-time click in the package settings UI. So this cannot self-heal; it warns
# LOUDLY instead, because the failure mode otherwise is a silent 401 in the store.
_ghcr_publish() {
  local image="$1"
  command -v gh >/dev/null 2>&1 || return 0
  local vis
  vis="$(gh api "/user/packages/container/${image}" --jq .visibility 2>/dev/null)" || return 0
  [ "$vis" = "public" ] && return 0
  echo "  !! GHCR package ${image} is ${vis} - unauthenticated pulls will 401." >&2
  echo "  !! Make it public once: https://github.com/users/diegonmarcos/packages/container/${image}/settings" >&2
}

_ghcr_source() {
  if [ -n "${GITHUB_REPOSITORY:-}" ]; then
    printf '%s/%s\n' "${GITHUB_SERVER_URL:-https://github.com}" "$GITHUB_REPOSITORY"
  else
    git remote get-url origin 2>/dev/null \
      | sed -e 's|^git@\([^:]*\):|https://\1/|' -e 's|\.git$||'
  fi
}


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

# ONE shared Cloud-constellation signing key — mirrors aa_cloud-superapp.
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
  vault="${VAULT_DIR:-$HOME/git/cloud-vault}"
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

# Guard against publishing an ABI-specific APK under an UNSUFFIXED name —
# the name every per-app registration and the store's default install URL
# point at, which is a promise the APK installs everywhere. 2026-08-31:
# media-center's x86_64 matrix job clobbered the arm64 asset under the
# unsuffixed name this way, leaving phones failing
# INSTALL_FAILED_NO_MATCHING_ABIS. Applies to every app, forever — this
# runs in the one place the sha256 sidecar is emitted, right before
# upload, so nothing can skip it.
_verify_asset_abi_neutral() {
  local f="$1" name; name="$(basename "$f")"
  # An explicitly ABI-suffixed name is a deliberate non-default variant —
  # ABI-specific content there is the point, not a bug.
  case "$name" in
    *-x86_64.apk|*-x86.apk|*-armeabi-v7a.apk|*-arm64-v8a.apk|*-arm64.apk) return 0 ;;
  esac
  # ABI listing without unzip: GitHub runners do not reliably ship it, and the
  # original "skip when unzip is missing" escape hatch is what let an
  # x86_64-only media-center APK publish under the unsuffixed name on
  # 2026-08-31 — the gate ran, found no unzip, warned, and returned success.
  # A safety gate that disables itself on the machine it must run on is not a
  # gate. python3 is present on every runner, so try it first and only fall
  # back to unzip; if NEITHER exists, fail rather than wave the asset through.
  local libs=""
  if command -v python3 >/dev/null 2>&1; then
    libs="$(python3 -c "import sys,zipfile
print(chr(10).join(n for n in zipfile.ZipFile(sys.argv[1]).namelist() if n.startswith('lib/')))" "$f" 2>/dev/null)"
  elif command -v unzip >/dev/null 2>&1; then
    libs="$(unzip -l "$f" 2>/dev/null | awk '{print $NF}' | grep '^lib/' || true)"
  else
    errlog "gh-release: neither python3 nor unzip available — cannot verify ABI neutrality of $name, refusing to publish it unsuffixed"
    exit 1
  fi
  if [ -n "$libs" ] && ! printf '%s\n' "$libs" | grep -q '^lib/arm64-v8a/'; then
    errlog "gh-release: $name carries native libs with none under lib/arm64-v8a/ — refusing to publish an ABI-specific APK under an unsuffixed/universal name (would break install on arm64 phones, INSTALL_FAILED_NO_MATCHING_ABIS)"
    errlog "  ABIs present: $(printf '%s\n' "$libs" | cut -d/ -f2 | sort -u | tr '\n' ' ')"
    exit 1
  fi
}

# Anonymous-pull probe + auto-delete gate. Call after EVERY successful GHCR
# push, in the SAME run. The GH Release is the only REQUIRED distribution
# channel for the store — it is public by construction, since a repo's
# release assets follow the repo's own visibility. GHCR is an OPTIONAL
# MIRROR the store's updater falls back off of on a clean 404. GitHub
# creates every brand-new user-owned package PRIVATE regardless of the
# repo, and there is no API to flip that after the fact — so a private
# mirror cannot self-heal. Worse than missing: it looks present (an
# authenticated HEAD succeeds) but 401s anonymously, so the store waits on
# it instead of falling through to the release. Delete it in the same run
# rather than leave that trap for the next check to discover. NEVER fails
# the build for this — the release already succeeded, and that is what
# actually matters; a failed delete only warns.
_ghcr_gate_public() {
  local namespace="$1" image="$2" tag="$3" registry="${4:-ghcr.io}"
  local scope="repository:${namespace}/${image}:pull"
  local token status
  # curl in CI/dev environments here can silently inject an ambient
  # Authorization header — strip it explicitly so this probe is truly
  # anonymous, not accidentally authenticated.
  token="$(curl -H "Authorization:" -sS --max-time 30 \
    "https://${registry}/token?scope=${scope}&service=${registry}" 2>/dev/null \
    | jq -r '.token // empty')"
  if [ -n "$token" ]; then
    status="$(curl -H "Authorization: Bearer $token" -sS --max-time 30 \
      -H "Accept: application/vnd.oci.image.manifest.v1+json,application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.v2+json" \
      -o /dev/null -w '%{http_code}' \
      "https://${registry}/v2/${namespace}/${image}/manifests/${tag}" 2>/dev/null)"
  fi
  [ -n "$token" ] && [ "$status" = "200" ] && return 0
  errlog "GHCR mirror ${namespace}/${image}:${tag} is NOT anonymously pullable (token=${token:+present}${token:-absent}, manifest=${status:-none}) — deleting it now."
  errlog "  The GH Release is the REQUIRED channel and is public by construction; a private GHCR package looks present-but-unreachable and blinds the store's updater instead of a clean 404 fallthrough."
  errlog "  A private mirror must not outlive the run that created it."
  command -v gh >/dev/null 2>&1 || { errlog "  gh CLI not found — cannot auto-delete; fix visibility manually at https://github.com/users/diegonmarcos/packages/container/${image}/settings"; return 0; }
  if gh api -X DELETE "/user/packages/container/${image}" >/dev/null 2>&1; then
    errlog "  deleted /user/packages/container/${image} — store will now see a clean 404 and fall through to the release."
  else
    errlog "  auto-delete FAILED for ${image} — package may still be private. Not failing the build: the release already succeeded and is what matters. Fix manually: https://github.com/users/diegonmarcos/packages/container/${image}/settings"
  fi
  return 0
}

case "$CMD" in
  build)
    log "Building Cloud Keyboard Libs APK (debug)…"
    _resolve_gif_keys
    mkdir -p "$DIST_DIR"
    _gradle :app:assembleDebug
    find "$SCRIPT_DIR/app/build/outputs/apk/debug" -name "*.apk" \
      -exec cp {} "$DIST_DIR/Cloud-Keyboard-Libs.apk" \;
    _enforce_signature "$DIST_DIR/Cloud-Keyboard-Libs.apk"
    log "APK → $DIST_DIR/Cloud-Keyboard-Libs.apk"
    ;;
  release)
    log "Building Cloud Keyboard Libs APK (release)…"
    _resolve_gif_keys
    _resolve_signing
    mkdir -p "$DIST_DIR"
    _gradle :app:assembleRelease
    find "$SCRIPT_DIR/app/build/outputs/apk/release" -name "*.apk" \
      -exec cp {} "$DIST_DIR/Cloud-Keyboard-Libs.apk" \;
    _enforce_signature "$DIST_DIR/Cloud-Keyboard-Libs.apk"
    log "APK → $DIST_DIR/Cloud-Keyboard-Libs.apk"
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
    ( cd "$DIST_DIR" && oras push "${reg}:latest"          "Cloud-Keyboard-Libs.apk:${mt}" \
    --annotation "org.opencontainers.image.source=$(_ghcr_source)" )
    ( cd "$DIST_DIR" && oras push "${reg}:sha-${SHORT}"    "Cloud-Keyboard-Libs.apk:${mt}" \
    --annotation "org.opencontainers.image.source=$(_ghcr_source)" )
    log "Pushed :latest + :sha-${SHORT}"
    registry="${reg%%/*}"; _ns_image="${reg#*/}"; namespace="${_ns_image%/*}"; image="${_ns_image##*/}"
    _ghcr_gate_public "$namespace" "$image" "sha-${SHORT}" "$registry"
    ;;
  gh-release)
    log "Publishing to GitHub Releases (rolling latest)…"
    # Sidecar sha256 — a same-size collision on 2026-08-30 made two distinct
    # APK builds compare "equal" under the old size-only update check,
    # silently hiding a real update. The in-app updater now compares this
    # digest instead, so every publish path must emit + verify it.
    _verify_asset_abi_neutral "$DIST_DIR/Cloud-Keyboard-Libs.apk"
    sha256sum "$DIST_DIR/Cloud-Keyboard-Libs.apk" | awk '{print $1}' > "$DIST_DIR/Cloud-Keyboard-Libs.apk.sha256"
    gh release upload latest "$DIST_DIR/Cloud-Keyboard-Libs.apk" "$DIST_DIR/Cloud-Keyboard-Libs.apk.sha256" --clobber 2>/dev/null \
      || gh release create latest \
           --title "Cloud Keyboard Libs (rolling)" \
           --notes "Auto-updated from main." \
           "$DIST_DIR/Cloud-Keyboard-Libs.apk" "$DIST_DIR/Cloud-Keyboard-Libs.apk.sha256"
    names="$(gh release view latest --json assets --jq '.assets[].name')"
    remote_size="$(gh release view latest --json assets --jq '.assets[] | select(.name=="Cloud-Keyboard-Libs.apk") | .size')"
    local_size="$(wc -c <"$DIST_DIR/Cloud-Keyboard-Libs.apk")"
    echo "$names" | grep -qxF "Cloud-Keyboard-Libs.apk" && echo "$names" | grep -qxF "Cloud-Keyboard-Libs.apk.sha256" \
      && [ -n "$remote_size" ] && [ "$remote_size" = "$local_size" ] \
      || { errlog "gh-release: Cloud-Keyboard-Libs.apk or its .sha256 sidecar missing/size-mismatched on release latest after upload (remote=$remote_size local=$local_size)"; exit 1; }
    ;;
  help|*)
    echo "Usage: build.sh <build|release|clean|oras-push|gh-release>"
    ;;
esac
