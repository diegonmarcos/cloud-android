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
  # THE PACKAGE MUST MATCH THE REPO. A public repo whose APK is private is not
  # a warning, it is a broken release: the constellation store pulls
  # unauthenticated and gets 401, which reads to a user as "the app is gone".
  #
  # GitHub creates every new USER-owned package private regardless of the repo,
  # links it via image.source without inheriting anything, and exposes no REST
  # endpoint to flip it (PATCH /user/packages/... is 404 even with
  # write:packages). So this cannot self-heal. What it CAN do is refuse to
  # report success: the mismatch fails the build, with the one URL that fixes
  # it, instead of leaving a 401 to be discovered by whoever tries to install.
  local image="$1"
  command -v gh >/dev/null 2>&1 || return 0
  local repo_vis pkg_vis want
  # An artifact may be DELIBERATELY private in a public repo — a fork whose
  # distribution is not ours to make, something not ready to be seen. That is a
  # decision this check must respect, not override: release.ghcr.visibility
  # states it, and where it is stated it wins over the repo. Without this the
  # check would push every exception toward being published, which is a worse
  # failure than the 401 it exists to prevent.
  want="$(_release_var '.release.ghcr.visibility')"
  if [ -n "$want" ] && [ "$want" != "null" ]; then
    repo_vis="$want"
  else
    repo_vis="$(gh repo view "${GITHUB_REPOSITORY:-$(_ghcr_source | sed 's|.*github.com/||')}" \
                  --json visibility --jq .visibility 2>/dev/null | tr 'A-Z' 'a-z')"
  fi
  [ -z "$repo_vis" ] && return 0
  pkg_vis="$(gh api "/user/packages/container/${image}" --jq .visibility 2>/dev/null)" || return 0
  [ "$pkg_vis" = "$repo_vis" ] && return 0
  errlog "GHCR visibility does not follow the repo."
  errlog "  repo    ${GITHUB_REPOSITORY:-$(_ghcr_source)} is ${repo_vis}"
  errlog "  package ${image} is ${pkg_vis} -> unauthenticated pulls 401"
  errlog "  GitHub creates user-owned packages private and offers no API to change it."
  errlog "  Fix once: https://github.com/users/diegonmarcos/packages/container/${image}/settings"
  errlog "  The GH Release asset is unaffected - it IS the repo, so it already follows."
  return 1
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
    ghcr_image="${reg##*/}"
    # CREATE WITH GITHUB_TOKEN, UPDATE WITH THE AMBIENT PAT LOGIN. Visibility is
    # fixed at CREATE time and there is no API to change it afterwards, so the
    # token is chosen per PACKAGE: absent package -> GITHUB_TOKEN, which links it
    # to this public repo and inherits that visibility. Inert for a package that
    # already exists, which is every one of these today.
    creds=()
    if [ -n "${GHCR_CREATE_TOKEN:-}" ] && command -v gh >/dev/null 2>&1 \
       && ! gh api "/user/packages/container/${ghcr_image}" >/dev/null 2>&1; then
      log "ghcr: ${ghcr_image} does not exist — creating it with GITHUB_TOKEN so it inherits the repo"
      creds=(--username "${GITHUB_ACTOR:-diegonmarcos}" --password "${GHCR_CREATE_TOKEN}")
    fi

    ( cd "$DIST_DIR" && oras push "${creds[@]}" "${reg}:latest"          "Cloud-Keyboard-Libs.apk:${mt}" \
    --annotation "org.opencontainers.image.source=$(_ghcr_source)" )
    ( cd "$DIST_DIR" && oras push "${creds[@]}" "${reg}:sha-${SHORT}"    "Cloud-Keyboard-Libs.apk:${mt}" \
    --annotation "org.opencontainers.image.source=$(_ghcr_source)" )
    log "Pushed :latest + :sha-${SHORT}"
    ;;
  gh-release)
    log "Publishing to GitHub Releases (rolling latest)…"
    # Sidecar sha256 — a same-size collision on 2026-08-30 made two distinct
    # APK builds compare "equal" under the old size-only update check,
    # silently hiding a real update. The in-app updater now compares this
    # digest instead, so every publish path must emit + verify it.
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
