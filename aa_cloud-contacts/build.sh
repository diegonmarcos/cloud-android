#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Cloud Contacts — Universal Build Dispatcher                                ║
# ║                                                                  ║
# ║ Standalone WebView multi-channel contacts app. Single-screen HTML/Tailwind UI.          ║
# ║ Single APK, gradle multi-module. All toolchain (AGP, gradle,      ║
# ║ kotlin, JDK, android-sdk) comes from flake.nix — never assume     ║
# ║ host has them.                                                    ║
# ║                                                                  ║
# ║ Commands:                                                        ║
# ║   build       gradle assembleDebug → dist/<release.artifact.debug>║
# ║   release     gradle assembleRelease (signed if keystore present) ║
# ║   dev         install + launch on connected device (adb)          ║
# ║   test        gradle test (JVM unit tests)                        ║
# ║   instrument  gradle connectedAndroidTest (needs device)          ║
# ║   lint        gradle lint                                         ║
# ║   clean       gradle clean + rm -rf dist/                         ║
# ║   shell       enter Nix devShell (gradle + sdk + jdk)             ║
# ║   ship        build + side-load via adb (USB-connected device)    ║
# ║   oras-push   push APK as OCI artifact → ghcr (release.ghcr block)║
# ║   oras-pull   pull APK from ghcr → dist/  [tag=latest]            ║
# ║   phone-install pull + copy to Android shared storage Download     ║
# ║   waydroid-install build + install APK into running Waydroid       ║
# ║   emulator    boot arm64 AVD (full-fidelity test; then `ship`)     ║
# ║   gh-release  attach APK to GitHub Release (release.gh_release)   ║
# ║                                                                  ║
# ║ NEVER bypass this script for build operations.                    ║
# ╚══════════════════════════════════════════════════════════════════╝
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="$SCRIPT_DIR/dist"
CMD="${1:-help}"
APP_MAIN="com.diegonmarcos.cloudcontacts.MainActivity"

log()    { printf "[%s] %s\n" "$(date '+%H:%M:%S')" "$1"; }
errlog() { printf "\033[0;31m[%s] ERROR: %s\033[0m\n" "$(date '+%H:%M:%S')" "$1" >&2; }

# Nix-wrapped invocation: every gradle call goes through `nix develop` so the
# JDK / AGP / Android SDK are reproducible per the flake. Set BYPASS_NIX=1 to
# use host tools (only for IDE / dev — never CI).
in_nix() {
  if [ "${BYPASS_NIX:-0}" = "1" ]; then
    "$@"
  else
    command -v nix >/dev/null 2>&1 || { errlog "nix not on PATH; install nix or set BYPASS_NIX=1"; exit 1; }
    nix develop "$SCRIPT_DIR" --command "$@"
  fi
}

# Heavy `.#emulator` devShell (emulator binary + arm64 system image).
in_nix_emulator() {
  if [ "${BYPASS_NIX:-0}" = "1" ]; then
    "$@"
  else
    command -v nix >/dev/null 2>&1 || { errlog "nix not on PATH; install nix or set BYPASS_NIX=1"; exit 1; }
    nix develop "$SCRIPT_DIR#emulator" --command "$@"
  fi
}

# Lightweight metadata commands (jq/git) prefer the host binary when present.
prefer_host() {
  if command -v "$1" >/dev/null 2>&1; then
    "$@"
  else
    in_nix "$@"
  fi
}

# ── signing-key resolver (build.json::signing → vault → env) ──────────
# Resolves the ONE shared Cloud-constellation key from vault and exports the
# ANDROID_KEYSTORE_* env that the gradle signingConfig reads, so local + CI
# sign identically. THERE IS NO FALLBACK: if the one shared key cannot be
# resolved the build FAILS LOUD (exit 1) — it never substitutes or generates
# another key. CI sets VAULT_DIR (vault checkout) + SOPS_AGE_KEY.
_resolve_signing() {
  local ks_rel sec_rel vault ks store_pw key_pw alias_
  # CI delivery (two-secret): if the workflow already populated a valid keystore
  # env from the ANDROID_KEYSTORE_B64 + creds GitHub secrets, trust it as-is —
  # still the ONE shared constellation key, just delivered via CI secret instead
  # of a vault checkout. Requires a real on-disk keystore + alias, so this is NOT
  # a fallback to a random/legacy key.
  # ONLY trust a pre-set keystore inside CI (GitHub Actions delivers the shared
  # key via the ANDROID_KEYSTORE_* secrets). LOCALLY we ignore any ambient env
  # and always resolve from the vault path below — so a stray ANDROID_KEYSTORE_*
  # pointing at a random keystore can never sign a local build.
  if [ -n "${GITHUB_ACTIONS:-}${CI:-}" ] \
     && [ -n "${ANDROID_KEYSTORE_FILE:-}" ] && [ -f "${ANDROID_KEYSTORE_FILE}" ] && [ -n "${ANDROID_KEY_ALIAS:-}" ]; then
    log "signing: using pre-set ANDROID_KEYSTORE_* (CI secret delivery)"
    return 0
  fi
  ks_rel="$(_release_var '.signing.vault_keystore')"
  sec_rel="$(_release_var '.signing.vault_secrets')"
  if [ -z "$ks_rel" ] || [ -z "$sec_rel" ]; then
    errlog "FATAL signing: .signing.vault_keystore/.vault_secrets are empty in build.json."
    errlog "  ALL constellation apps MUST sign with the ONE shared key:"
    errlog "    vault/A0_keys/providers/android/release.jks (OU=Cloud Constellation)"
    errlog "  Set both paths in build.json::signing. Refusing to build with any other key."
    exit 1
  fi
  vault="${VAULT_DIR:-$HOME/git/cloud-vault}"
  ks="$vault/$ks_rel"
  if [ ! -f "$ks" ]; then
    errlog "FATAL signing: the ONE shared constellation keystore is missing at $ks"
    errlog "  Check out the vault repo (set VAULT_DIR if elsewhere). NO random/legacy fallback key is allowed."
    exit 1
  fi
  command -v sops >/dev/null 2>&1 || { errlog "FATAL signing: sops not on PATH; cannot decrypt the shared key. Refusing to build."; exit 1; }
  store_pw="$(sops --config /dev/null -d --extract '["keystore_password"]' "$vault/$sec_rel" 2>/dev/null || true)"
  key_pw="$(sops --config /dev/null -d --extract '["key_password"]' "$vault/$sec_rel" 2>/dev/null || true)"
  alias_="$(sops --config /dev/null -d --extract '["key_alias"]' "$vault/$sec_rel" 2>/dev/null || true)"
  if [ -z "$store_pw" ] || [ -z "$alias_" ]; then
    errlog "FATAL signing: cannot decrypt $sec_rel (need SOPS_AGE_KEY / SOPS_AGE_KEY_FILE)."
    errlog "  The ONE shared constellation key must be used — refusing to fall back to any other key."
    exit 1
  fi
  export ANDROID_KEYSTORE_FILE="$ks"
  export ANDROID_KEYSTORE_PASSWORD="$store_pw"
  export ANDROID_KEY_PASSWORD="$key_pw"
  export ANDROID_KEY_ALIAS="$alias_"
  log "signing: ONE shared constellation key (alias $alias_) from vault/$ks_rel"
}

# ── signature enforcement gate — the ONE guarantee ───────────────────────
# Unconditionally normalize EVERY emitted APK to the ONE shared constellation
# key: zipalign + apksigner sign with the vault key (_resolve_signing has NO
# fallback — it is the shared key or the build dies), then apksigner verify to
# prove the result is a valid signature. Whatever gradle/upstream produced
# (debug key, vendor key, fork keystore, unsigned) is overwritten — it is
# impossible to ship anything but the shared key.
_enforce_signature() {
  local apk="$1" bt zipalign apksigner
  [ -f "$apk" ] || { errlog "sign-enforce: missing APK $apk"; exit 1; }
  _resolve_signing
  bt="$(ls -d "${ANDROID_HOME:-/nonexistent}"/build-tools/* 2>/dev/null | sort -V | tail -1)"
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

step_build() {
  log "Build: $(_release_var '.name') (debug APK)"
  _resolve_signing
  _export_variant_abis
  in_nix gradle :app:assembleDebug
  mkdir -p "$DIST_DIR"
  local out="$DIST_DIR/$(_variant_artifact)"
  cp "$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk" "$out"
  _enforce_signature "$out"
  log "→ $out"
}

step_release() {
  log "Build: $(_release_var '.name') (release APK)"
  _resolve_signing
  in_nix gradle :app:assembleRelease
  mkdir -p "$DIST_DIR"
  local out="$DIST_DIR/$(_release_var '.release.artifact.release')"
  cp "$SCRIPT_DIR/app/build/outputs/apk/release/app-release.apk" "$out" 2>/dev/null \
    || cp "$SCRIPT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk" "${out%.apk}-unsigned.apk"
  if [ -f "$out" ]; then _enforce_signature "$out"; else _enforce_signature "${out%.apk}-unsigned.apk"; fi
  log "→ $DIST_DIR/"
}

step_dev() {
  log "Dev: launching on connected device (adb)"
  command -v adb >/dev/null || in_nix adb devices
  in_nix gradle :app:installDebug
  in_nix adb shell am start -n "$(_release_var '.android.application_id')/$APP_MAIN"
}

step_test()       { log "Test: JVM unit tests"; in_nix gradle test; }
step_instrument() { log "Test: instrumented (needs device)"; in_nix gradle connectedAndroidTest; }
step_lint()       { log "Lint"; in_nix gradle lint; }
step_clean()      { log "Clean"; in_nix gradle clean; rm -rf "$DIST_DIR"; }
step_shell()      { log "Entering Nix devShell"; exec nix develop "$SCRIPT_DIR"; }

step_ship() {
  step_build
  log "Ship: side-loading via adb"
  in_nix adb install -r "$DIST_DIR/$(_variant_artifact)"
}

step_waydroid_install() {
  # Build + install the APK into a running Waydroid session on THIS host.
  step_build
  local apk app_id
  apk="$DIST_DIR/$(_variant_artifact)"
  app_id="$(_release_var '.android.application_id')"

  command -v waydroid >/dev/null 2>&1 || {
    errlog "waydroid not on PATH — enable it in the NixOS host flake"; exit 1; }
  if ! waydroid status 2>/dev/null | grep -q "Session.*RUNNING"; then
    errlog "no running Waydroid session — start one first: waydroid-launch"; exit 1
  fi
  [ -f "$apk" ] || { errlog "APK not found: $apk (step_build failed?)"; exit 1; }

  log "Waydroid: installing $apk"
  waydroid app install "$apk"
  log "✓ installed → launch with: waydroid app launch $app_id"
}

step_emulator() {
  # Boot an arm64 AVD for full-fidelity testing. Data-driven from
  # build.json::emulator. Once up it registers as an adb device, so
  # `./build.sh ship` installs straight into it.
  local avd img device
  avd="$(_release_var '.emulator.avd_name')"
  img="$(_release_var '.emulator.system_image')"
  device="$(_release_var '.emulator.device')"
  [ -n "$avd" ] || { errlog "build.json .emulator.avd_name missing"; exit 1; }
  [ -n "$img" ] || { errlog "build.json .emulator.system_image missing"; exit 1; }

  if ! in_nix_emulator avdmanager list avd 2>/dev/null | grep -q "Name: $avd"; then
    log "Creating AVD '$avd' ($img${device:+, device=$device})"
    if [ -n "$device" ]; then
      printf 'no\n' | in_nix_emulator avdmanager create avd -n "$avd" -k "$img" --device "$device" --force
    else
      printf 'no\n' | in_nix_emulator avdmanager create avd -n "$avd" -k "$img" --force
    fi
  fi

  local boot_args=()
  mapfile -t boot_args < <(prefer_host jq -r '.emulator.boot_args[]? // empty' "$SCRIPT_DIR/build.json")

  log "Booting emulator '$avd' (arm64 — software-emulated; first boot is slow)"
  log "  → in another shell: ./build.sh ship   (build + adb install into it)"
  in_nix_emulator emulator -avd "$avd" "${boot_args[@]}" "$@"
}

# ── data-driven release helpers ────────────────────────────────────────
_release_var() {
  prefer_host jq -r "$1 // empty" "$SCRIPT_DIR/build.json"
}

# ── ABI variant helpers ────────────────────────────────────────────────
# CLOUDCONTACTS_VARIANT (env) selects a release.variants[] entry. Unset = arm64
# default → every helper falls back to the legacy single-variant keys.
_variant_field() {
  local v="${CLOUDCONTACTS_VARIANT:-}"
  [ -z "$v" ] && return 0
  prefer_host jq -r --arg v "$v" \
    '(.release.variants[]? | select(.id==$v) | '"$1"') // empty' "$SCRIPT_DIR/build.json"
}

_variant_artifact() {
  local n; n="$(_variant_field '.artifact_debug')"
  [ -n "$n" ] && { echo "$n"; return; }
  _release_var '.release.artifact.debug'
}

_variant_gh_asset() {
  local n; n="$(_variant_field '.gh_asset')"
  [ -n "$n" ] && { echo "$n"; return; }
  _resolve_template "$(_release_var '.release.gh_release.asset_name')"
}

_variant_tag_suffix() { _variant_field '.ghcr_tag_suffix'; }

# Export CLOUDCONTACTS_ABIS (CSV) for gradle from the active variant. No-op when
# unset → gradle reads build.json::android.abi_filters.
_export_variant_abis() {
  local csv; csv="$(_variant_field '.abis | join(",")')"
  if [ -n "$csv" ]; then
    export CLOUDCONTACTS_ABIS="$csv"
    log "Variant ${CLOUDCONTACTS_VARIANT:-}: ABIs=$csv"
  fi
  return 0
}

_resolve_template() {
  local tmpl="$1"
  local sha="${GITHUB_SHA:-$(prefer_host git -C "$SCRIPT_DIR" rev-parse --short=8 HEAD 2>/dev/null || echo unknown)}"
  local ver="$(_release_var '.android.version_name')"
  echo "${tmpl//\{sha\}/${sha:0:8}}" | sed "s|{version_name}|$ver|g"
}

step_oras_push() {
  local enabled registry namespace image media_type artifact
  enabled="$(_release_var '.release.ghcr.enabled')"
  [ "$enabled" = "true" ] || { log "oras-push: release.ghcr.enabled=false — skip"; return 0; }

  registry="$(_release_var '.release.ghcr.registry')"
  namespace="$(_release_var '.release.ghcr.namespace')"
  image="$(_release_var '.release.ghcr.image')"
  media_type="$(_release_var '.release.ghcr.media_type')"

  if   [ -f "$DIST_DIR/$(_variant_artifact)" ]; then
    artifact="$DIST_DIR/$(_variant_artifact)"
  elif [ -f "$DIST_DIR/$(_release_var '.release.artifact.release')" ]; then
    artifact="$DIST_DIR/$(_release_var '.release.artifact.release')"
  elif [ -f "$DIST_DIR/$(_release_var '.release.artifact.debug')" ]; then
    artifact="$DIST_DIR/$(_release_var '.release.artifact.debug')"
  else
    errlog "oras-push: no APK found in $DIST_DIR — run build/release first"; exit 1
  fi

  local artifact_dir artifact_name
  artifact_dir="$(dirname "$artifact")"
  artifact_name="$(basename "$artifact")"

  # Code-identity stamp on the manifest: the short git sha (matches the app's
  # BuildConfig.GIT_SHORT_SHA). The in-app updater compares this to its own sha
  # and SKIPS the download when they match — so a non-reproducible rebuild of
  # identical code never prompts a spurious update.
  local rev
  rev="${GITHUB_SHA:-$(prefer_host git -C "$SCRIPT_DIR" rev-parse HEAD 2>/dev/null || echo unknown)}"
  rev="${rev:0:8}"

  local tags
  tags="$(prefer_host jq -r '.release.ghcr.tags[]' "$SCRIPT_DIR/build.json")"
  local suffix; suffix="$(_variant_tag_suffix)"
  while IFS= read -r tmpl; do
    [ -z "$tmpl" ] && continue
    local tag ref
    tag="$(_resolve_template "$tmpl")${suffix}"
    ref="$registry/$namespace/$image:$tag"
    log "oras push $ref ← $artifact_name (rev $rev)"
    ( cd "$artifact_dir" && in_nix oras push "$ref" "$artifact_name:$media_type" \
        --artifact-type "$media_type" \
        --annotation "org.opencontainers.image.revision=$rev" )
  done <<< "$tags"
}

step_oras_pull() {
  local registry namespace image tag
  registry="$(_release_var '.release.ghcr.registry')"
  namespace="$(_release_var '.release.ghcr.namespace')"
  image="$(_release_var '.release.ghcr.image')"
  tag="${2:-$(_release_var '.release.phone_install.default_tag')}"
  tag="${tag:-latest}"

  local repo="$namespace/$image"
  local token manifest digest size asset_title
  log "oras-pull: $registry/$repo:$tag (via OCI HTTP API)"

  token="$(curl -sf "https://$registry/token?service=$registry&scope=repository:$repo:pull" | jq -r .token)"
  [ -n "$token" ] && [ "$token" != "null" ] || { errlog "no bearer token"; exit 1; }

  mkdir -p "$DIST_DIR"
  manifest="$(curl -sfL \
    -H "Authorization: Bearer $token" \
    -H "Accept: application/vnd.oci.image.manifest.v1+json" \
    "https://$registry/v2/$repo/manifests/$tag")"
  digest="$(jq -r '.layers[0].digest' <<<"$manifest")"
  size="$(jq -r '.layers[0].size' <<<"$manifest")"
  asset_title="$(jq -r '.layers[0].annotations["org.opencontainers.image.title"] // "cloud-contacts.apk"' <<<"$manifest")"
  [ -n "$digest" ] && [ "$digest" != "null" ] || { errlog "manifest has no layers"; exit 1; }

  local out="$DIST_DIR/$asset_title"
  log "  pulling $digest ($size bytes) → $out"
  curl -sfL -H "Authorization: Bearer $token" \
    "https://$registry/v2/$repo/blobs/$digest" -o "$out"

  local got_sha
  got_sha="$(sha256sum "$out" | cut -d' ' -f1)"
  if [ "sha256:$got_sha" != "$digest" ]; then
    errlog "digest mismatch — got sha256:$got_sha, expected $digest"
    exit 1
  fi
  log "  ✓ $out (sha256:$got_sha)"
}

step_phone_install() {
  step_oras_pull "$@"

  local target_dir asset_name src
  target_dir="${PHONE_TARGET:-$(_release_var '.release.phone_install.target_dir')}"
  target_dir="${target_dir/#\~/$HOME}"
  asset_name="$(_release_var '.release.phone_install.asset_name')"

  src="$(ls -1t "$DIST_DIR"/*.apk 2>/dev/null | head -1)"
  [ -f "$src" ] || { errlog "no APK in $DIST_DIR — oras-pull failed silently"; exit 1; }

  if [ ! -d "$target_dir" ]; then
    errlog "phone-install: $target_dir does not exist"
    errlog "  Termux: run 'termux-setup-storage' on the phone and accept the prompt"
    errlog "  Other:  set PHONE_TARGET=/path/to/dir env var"
    exit 1
  fi

  cp "$src" "$target_dir/$asset_name"
  log "✓ $target_dir/$asset_name"
  log "  Open Files app → Download → tap APK → install"
}

step_gh_release() {
  local enabled draft prerelease notes asset rolling_tag
  enabled="$(_release_var '.release.gh_release.enabled')"
  [ "$enabled" = "true" ] || { log "gh-release: enabled=false — skip"; return 0; }

  draft="$(_release_var '.release.gh_release.draft')"
  prerelease="$(_release_var '.release.gh_release.prerelease')"
  notes="$(_release_var '.release.gh_release.generate_release_notes')"
  asset="$(_variant_gh_asset)"
  rolling_tag="$(_release_var '.release.gh_release.rolling_tag')"

  local src_variant="$DIST_DIR/$(_variant_artifact)"
  local src_release="$DIST_DIR/$(_release_var '.release.artifact.release')"
  local src_debug="$DIST_DIR/$(_release_var '.release.artifact.debug')"
  local dst="$DIST_DIR/$asset"
  if   [ -f "$src_variant" ] && [ "$src_variant" != "$dst" ]; then cp "$src_variant" "$dst"
  elif [ -f "$src_release" ] && [ "$src_release" != "$dst" ]; then cp "$src_release" "$dst"
  elif [ -f "$src_debug"   ] && [ "$src_debug"   != "$dst" ]; then cp "$src_debug"   "$dst"
  fi
  [ -f "$dst" ] || { errlog "gh-release: staged asset $dst missing — no APK in $DIST_DIR?"; exit 1; }

  if [ -n "$rolling_tag" ] && [ "$rolling_tag" != "null" ]; then
    log "gh-release: rolling mode — tag=$rolling_tag ← $asset"
    if ! in_nix gh release view "$rolling_tag" >/dev/null 2>&1; then
      local create_flags=("$rolling_tag" --title "$rolling_tag" --target "${GITHUB_SHA:-main}" --notes "Rolling release — overwritten on every main push." --latest)
      [ "$draft" = "true" ]      && create_flags+=(--draft)
      [ "$prerelease" = "true" ] && create_flags+=(--prerelease)
      in_nix gh release create "${create_flags[@]}"
    fi
    in_nix gh release upload "$rolling_tag" "$DIST_DIR/$asset" --clobber
    in_nix gh release edit "$rolling_tag" --latest >/dev/null 2>&1 || true
  fi

  local is_tag_push=0
  case "${GITHUB_REF:-}" in refs/tags/*) is_tag_push=1 ;; esac
  if [ "$is_tag_push" = "1" ] && [ -n "${GITHUB_REF_NAME:-}" ]; then
    local flags=("$GITHUB_REF_NAME" "$DIST_DIR/$asset" --title "$GITHUB_REF_NAME")
    [ "$draft" = "true" ]      && flags+=(--draft)
    [ "$prerelease" = "true" ] && flags+=(--prerelease)
    [ "$notes" = "true" ]      && flags+=(--generate-notes)
    log "gh release create $GITHUB_REF_NAME ← $asset"
    in_nix gh release create "${flags[@]}"
  elif [ -z "$rolling_tag" ] || [ "$rolling_tag" = "null" ]; then
    errlog "gh-release: neither rolling_tag set nor under a tag push — nothing to publish"
    exit 1
  fi
}

case "$CMD" in
  build)      step_build ;;
  release)    step_release ;;
  dev)        step_dev ;;
  test)       step_test ;;
  instrument) step_instrument ;;
  lint)       step_lint ;;
  clean)      step_clean ;;
  shell)      step_shell ;;
  ship)       step_ship ;;
  oras-push)    step_oras_push ;;
  oras-pull)    step_oras_pull "$@" ;;
  phone-install) step_phone_install "$@" ;;
  waydroid-install) step_waydroid_install "$@" ;;
  emulator)     step_emulator "$@" ;;
  gh-release)   step_gh_release ;;
  help|*)
    sed -n '2,/^set -euo/p' "$0" | sed 's/^# *//; /^set/d; /^$/d'
    ;;
esac
