#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Cloud SuperApp — Universal Build Dispatcher                      ║
# ║                                                                  ║
# ║ Modularized monolith Android app. Single APK, gradle multi-module. ║
# ║ All toolchain (AGP, gradle, kotlin, JDK, android-sdk) comes from   ║
# ║ flake.nix — never assume host has them.                            ║
# ║                                                                  ║
# ║ Commands:                                                        ║
# ║   build       gradle assembleDebug → dist/<release.artifact.debug> ║
# ║   release     gradle assembleRelease (signed if keystore present) ║
# ║   dev         open IDE / run on connected device (adb)            ║
# ║   test        gradle test (JVM unit tests)                        ║
# ║   instrument  gradle connectedAndroidTest (needs device)          ║
# ║   lint        gradle lint                                         ║
# ║   clean       gradle clean + rm -rf dist/                         ║
# ║   shell       enter Nix devShell (gradle + sdk + jdk)             ║
# ║   ship        build + side-load via adb (USB-connected device)    ║
# ║   oras-push   push APK as OCI artifact → ghcr (release.ghcr block)║
# ║   oras-pull   pull APK from ghcr → dist/  [tag=latest]            ║
# ║   phone-install pull + copy to Android shared storage Download     ║
# ║   waydroid-install build + install APK into running Waydroid session ║
# ║   emulator     boot arm64 AVD (full-fidelity test; then `ship` to it) ║
# ║   gh-release  attach APK to GitHub Release (release.gh_release)   ║
# ║   sync-qrcodes  pull qrcodes.json from front/linktree → assets/    ║
# ║   sync-net      cherry-pick wireguard-android tunnel/ → libs/net/  ║
# ║   sync-heliboard cherry-pick HeliBoard app/src/main → libs/keyboard/║
# ║                                                                  ║
# ║ NEVER bypass this script for build operations.                    ║
# ╚══════════════════════════════════════════════════════════════════╝
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="$SCRIPT_DIR/dist"
CMD="${1:-help}"

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

# Like in_nix but uses the heavy `.#emulator` devShell (emulator binary +
# arm64 system image). Only `build.sh emulator` needs it — keeping these out
# of the default shell means a plain `build.sh build` never realises the
# ~hundreds-of-MB emulator/system-image closure.
in_nix_emulator() {
  if [ "${BYPASS_NIX:-0}" = "1" ]; then
    "$@"
  else
    command -v nix >/dev/null 2>&1 || { errlog "nix not on PATH; install nix or set BYPASS_NIX=1"; exit 1; }
    nix develop "$SCRIPT_DIR#emulator" --command "$@"
  fi
}

# Lightweight metadata commands (jq for build.json, git for HEAD sha, etc.)
# don't need the Android devShell. On hosts where the devShell isn't
# usable (e.g. aarch64 Termux — the Android SDK derivation is x86-only),
# prefer the host binary when it's on PATH. Falls back to nix only if
# the tool genuinely isn't installed.
prefer_host() {
  if command -v "$1" >/dev/null 2>&1; then
    "$@"
  else
    in_nix "$@"
  fi
}

# ── signing-key resolver (build.json::signing → vault → env) ──────────
# Resolves the SHARED Cloud-constellation key from vault and exports the
# ANDROID_KEYSTORE_* env that app/build.gradle's signingConfig reads, so
# local builds + CI produce a byte-identical signature. There is NO
# fallback: if the ONE shared constellation key / sops / age key isn't
# available the build FAILS LOUD (exit 1) — it never substitutes or
# generates another key.
# CI sets VAULT_DIR (vault checkout) + SOPS_AGE_KEY so it resolves there too.
_resolve_signing() {
  local ks_rel sec_rel vault ks store_pw key_pw alias_
  # CI delivery (two-secret): if the workflow already populated a valid keystore
  # env from the ANDROID_KEYSTORE_B64 + creds GitHub secrets, trust it as-is —
  # still the ONE shared constellation key, just delivered via CI secret instead
  # of a vault checkout. Requires a real on-disk keystore + alias, so this is NOT
  # a fallback to a random/legacy key.
  if [ -n "${ANDROID_KEYSTORE_FILE:-}" ] && [ -f "${ANDROID_KEYSTORE_FILE}" ] && [ -n "${ANDROID_KEY_ALIAS:-}" ]; then
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
  vault="${VAULT_DIR:-$HOME/git/vault}"
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

step_build() {
  log "Build: $(_release_var '.name') (debug APK)"
  _resolve_signing
  _export_variant_abis
  in_nix gradle :app:assembleDebug
  mkdir -p "$DIST_DIR"
  local out="$DIST_DIR/$(_variant_artifact)"
  cp "$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk" "$out"
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
  log "→ $DIST_DIR/"
}

step_dev() {
  log "Dev: launching on connected device (adb)"
  command -v adb >/dev/null || in_nix adb devices
  in_nix gradle :app:installDebug
  in_nix adb shell am start -n "$(_release_var '.android.application_id')/com.diegonmarcos.superapp.MainActivity"
}

step_test()       { log "Test: JVM unit tests"; in_nix gradle test; }
step_instrument() { log "Test: instrumented (needs device)"; in_nix gradle connectedAndroidTest; }
step_lint()       { log "Lint"; in_nix gradle lint; }
step_clean()      { log "Clean"; in_nix gradle clean; rm -rf "$DIST_DIR"; }
step_shell()      { log "Entering Nix devShell"; exec nix develop "$SCRIPT_DIR"; }

step_ship() {
  step_build
  log "Ship: side-loading via adb"
  in_nix adb install -r "$DIST_DIR/$(_release_var '.release.artifact.debug')"
}

step_waydroid_install() {
  # Build + install the APK into a running Waydroid session on THIS host.
  # `waydroid` is a system command (NixOS host flake), not part of the Android
  # devShell — call it directly, not via in_nix. Artifact + app-id come from
  # build.json (data-driven), honouring SUPERAPP_VARIANT.
  #   • Default (arm64) install on x86_64 Waydroid needs the ARM bridge
  #     (libhoudini / libndk_translation) from the host's waydroid bootstrap.
  #   • SUPERAPP_VARIANT=x86_64 builds a NATIVE x86_64 APK — no bridge needed;
  #     this is the recommended path on the Surface desktop.
  step_build
  local apk app_id
  apk="$DIST_DIR/$(_variant_artifact)"
  app_id="$(_release_var '.android.application_id')"

  command -v waydroid >/dev/null 2>&1 || {
    errlog "waydroid not on PATH — enable it in the NixOS host flake (configuration_containers.nix)"; exit 1; }
  if ! waydroid status 2>/dev/null | grep -q "Session.*RUNNING"; then
    errlog "no running Waydroid session — start one first: waydroid-launch"; exit 1
  fi
  [ -f "$apk" ] || { errlog "APK not found: $apk (step_build failed?)"; exit 1; }

  log "Waydroid: installing $apk"
  waydroid app install "$apk"
  log "✓ installed → launch with: waydroid app launch $app_id"
}

step_emulator() {
  # Boot an arm64 AVD for FULL-FIDELITY testing — the emulator emulates arm64
  # wholesale (no libhoudini translation, unlike the Waydroid path). Slow on
  # first boot. Data-driven from build.json::emulator. Once up it registers as
  # an adb device, so `./build.sh ship` (or dev) installs straight into it —
  # same code path as a USB phone.
  local avd img device
  avd="$(_release_var '.emulator.avd_name')"
  img="$(_release_var '.emulator.system_image')"
  device="$(_release_var '.emulator.device')"
  [ -n "$avd" ] || { errlog "build.json .emulator.avd_name missing"; exit 1; }
  [ -n "$img" ] || { errlog "build.json .emulator.system_image missing"; exit 1; }

  # Create the AVD once (idempotent). avdmanager prompts for a custom hardware
  # profile → answer "no".
  if ! in_nix_emulator avdmanager list avd 2>/dev/null | grep -q "Name: $avd"; then
    log "Creating AVD '$avd' ($img${device:+, device=$device})"
    if [ -n "$device" ]; then
      printf 'no\n' | in_nix_emulator avdmanager create avd -n "$avd" -k "$img" --device "$device" --force
    else
      printf 'no\n' | in_nix_emulator avdmanager create avd -n "$avd" -k "$img" --force
    fi
  fi

  # boot_args are data-driven (build.json::emulator.boot_args).
  local boot_args=()
  mapfile -t boot_args < <(prefer_host jq -r '.emulator.boot_args[]? // empty' "$SCRIPT_DIR/build.json")

  log "Booting emulator '$avd' (arm64 — software-emulated; first boot is slow)"
  log "  → in another shell: ./build.sh ship   (build + adb install into it)"
  in_nix_emulator emulator -avd "$avd" "${boot_args[@]}" "$@"
}

# ── data-driven release helpers ────────────────────────────────────────
# All registry / tag / asset config lives in build.json::release. Nothing
# hardcoded here — pure interpolation. {sha} and {version_name} are the
# only template variables.
_release_var() {
  prefer_host jq -r "$1 // empty" "$SCRIPT_DIR/build.json"
}

# ── ABI variant helpers ────────────────────────────────────────────────
# SUPERAPP_VARIANT (env) selects a release.variants[] entry. Unset = arm64
# default → every helper falls back to the legacy single-variant keys, so
# the arm64 path is byte-identical to before. _variant_field reads one
# field off the selected variant (empty when unset / not found).
_variant_field() {
  local v="${SUPERAPP_VARIANT:-}"
  [ -z "$v" ] && return 0
  prefer_host jq -r --arg v "$v" \
    '(.release.variants[]? | select(.id==$v) | '"$1"') // empty' "$SCRIPT_DIR/build.json"
}

# dist/ artifact filename for the active variant (default: legacy debug key).
_variant_artifact() {
  local n; n="$(_variant_field '.artifact_debug')"
  [ -n "$n" ] && { echo "$n"; return; }
  _release_var '.release.artifact.debug'
}

# GitHub-release asset filename for the active variant.
_variant_gh_asset() {
  local n; n="$(_variant_field '.gh_asset')"
  [ -n "$n" ] && { echo "$n"; return; }
  _resolve_template "$(_release_var '.release.gh_release.asset_name')"
}

# GHCR tag suffix for the active variant ("" for arm64).
_variant_tag_suffix() { _variant_field '.ghcr_tag_suffix'; }

# Export SUPERAPP_ABIS (CSV) for gradle from the active variant. No-op when
# unset → gradle reads build.json::android.abi_filters.
_export_variant_abis() {
  # NOTE: must end with a TRUE status. Called bare in step_build under
  # `set -e`; a trailing `[ -n "$csv" ] && {…}` returns 1 on the default
  # (no-variant) path where csv is empty → aborts the build. Use an if-block.
  local csv; csv="$(_variant_field '.abis | join(",")')"
  if [ -n "$csv" ]; then
    export SUPERAPP_ABIS="$csv"
    log "Variant ${SUPERAPP_VARIANT:-}: ABIs=$csv"
  fi
  return 0
}

_resolve_template() {
  # Expand {sha} → GITHUB_SHA[:8] (or git rev-parse --short=8), {version_name} → build.json
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

  # Active-variant artifact first (x86_64 build only produces that one),
  # then release, then legacy debug.
  if   [ -f "$DIST_DIR/$(_variant_artifact)" ]; then
    artifact="$DIST_DIR/$(_variant_artifact)"
  elif [ -f "$DIST_DIR/$(_release_var '.release.artifact.release')" ]; then
    artifact="$DIST_DIR/$(_release_var '.release.artifact.release')"
  elif [ -f "$DIST_DIR/$(_release_var '.release.artifact.debug')" ]; then
    artifact="$DIST_DIR/$(_release_var '.release.artifact.debug')"
  else
    errlog "oras-push: no APK found in $DIST_DIR — run build/release first"; exit 1
  fi

  # ORAS rejects absolute file paths (artifact name = path → leaks host
  # filesystem). Push from the artifact's dir using only the basename.
  local artifact_dir artifact_name
  artifact_dir="$(dirname "$artifact")"
  artifact_name="$(basename "$artifact")"

  # Code-identity stamp (short git sha; matches BuildConfig.GIT_SHORT_SHA) so the
  # in-app updater skips an identical-code rebuild instead of prompting.
  local rev
  rev="${GITHUB_SHA:-$(prefer_host git -C "$SCRIPT_DIR" rev-parse HEAD 2>/dev/null || echo unknown)}"
  rev="${rev:0:8}"

  # Iterate templated tags from build.json (data-driven, NO hardcoded list).
  local tags
  tags="$(prefer_host jq -r '.release.ghcr.tags[]' "$SCRIPT_DIR/build.json")"
  local suffix; suffix="$(_variant_tag_suffix)"
  while IFS= read -r tmpl; do
    [ -z "$tmpl" ] && continue
    local tag ref
    # Append the variant's GHCR tag suffix ("" for arm64) so x86_64 lands
    # on :latest-x86_64 / :sha-<sha>-x86_64 / :v<ver>-x86_64.
    tag="$(_resolve_template "$tmpl")${suffix}"
    ref="$registry/$namespace/$image:$tag"
    log "oras push $ref ← $artifact_name (rev $rev)"
    ( cd "$artifact_dir" && in_nix oras push "$ref" "$artifact_name:$media_type" \
        --artifact-type "$media_type" \
        --annotation "org.opencontainers.image.revision=$rev" )
  done <<< "$tags"
}

step_oras_pull() {
  # Pull APK from GHCR via curl + OCI HTTP API (no oras binary needed).
  # Public packages: anonymous bearer token from ghcr.io/token works.
  # All registry/namespace/image come from build.json::release.ghcr.
  # Optional 2nd arg: tag (default from release.phone_install.default_tag).
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
  asset_title="$(jq -r '.layers[0].annotations["org.opencontainers.image.title"] // "superapp.apk"' <<<"$manifest")"
  [ -n "$digest" ] && [ "$digest" != "null" ] || { errlog "manifest has no layers"; exit 1; }

  local out="$DIST_DIR/$asset_title"
  log "  pulling $digest ($size bytes) → $out"
  curl -sfL -H "Authorization: Bearer $token" \
    "https://$registry/v2/$repo/blobs/$digest" -o "$out"

  # Verify digest matches what the manifest claimed.
  local got_sha
  got_sha="$(sha256sum "$out" | cut -d' ' -f1)"
  if [ "sha256:$got_sha" != "$digest" ]; then
    errlog "digest mismatch — got sha256:$got_sha, expected $digest"
    exit 1
  fi
  log "  ✓ $out (sha256:$got_sha)"
}

step_phone_install() {
  # Pull APK + copy to a target dir an Android file manager can see.
  # target_dir from build.json::release.phone_install; override via PHONE_TARGET env.
  step_oras_pull "$@"

  local target_dir asset_name src
  target_dir="${PHONE_TARGET:-$(_release_var '.release.phone_install.target_dir')}"
  # Expand ~ manually — jq returns the literal "~/...".
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
  local enabled draft prerelease notes asset_tmpl asset rolling_tag
  enabled="$(_release_var '.release.gh_release.enabled')"
  [ "$enabled" = "true" ] || { log "gh-release: enabled=false — skip"; return 0; }

  draft="$(_release_var '.release.gh_release.draft')"
  prerelease="$(_release_var '.release.gh_release.prerelease')"
  notes="$(_release_var '.release.gh_release.generate_release_notes')"
  asset_tmpl="$(_release_var '.release.gh_release.asset_name')"
  # Variant's asset filename (falls back to the resolved default asset_name).
  asset="$(_variant_gh_asset)"
  rolling_tag="$(_release_var '.release.gh_release.rolling_tag')"

  # Stage asset with the requested filename. Prefer the release-variant
  # APK when present; fall back to the debug variant (CI's main-push
  # build is debug). Skip the cp when source == destination — happens
  # when asset_name equals one of the artifact filenames (e.g. rolling
  # mode where we set asset_name="Cloud-SuperApp.apk" matching the debug
  # artifact name directly).
  local src_variant="$DIST_DIR/$(_variant_artifact)"
  local src_release="$DIST_DIR/$(_release_var '.release.artifact.release')"
  local src_debug="$DIST_DIR/$(_release_var '.release.artifact.debug')"
  local dst="$DIST_DIR/$asset"
  # Active-variant APK first (x86_64 only produces that), then release, then
  # legacy debug. The cp is skipped when source == destination (the common
  # case: asset_name already equals the artifact filename).
  if   [ -f "$src_variant" ] && [ "$src_variant" != "$dst" ]; then cp "$src_variant" "$dst"
  elif [ -f "$src_release" ] && [ "$src_release" != "$dst" ]; then cp "$src_release" "$dst"
  elif [ -f "$src_debug"   ] && [ "$src_debug"   != "$dst" ]; then cp "$src_debug"   "$dst"
  fi
  [ -f "$dst" ] || { errlog "gh-release: staged asset $dst missing — no APK in $DIST_DIR?"; exit 1; }

  # ─── Rolling release (mode B) ───────────────────────────────────────
  # When release.gh_release.rolling_tag is set, the engine publishes
  # the APK to a SINGLE GitHub Release with that tag, overwriting any
  # existing asset (--clobber). This is what the ship workflow runs on
  # every main push so /releases/latest/download/<asset_name> is a
  # permanent download URL the linktree footer can link to.
  if [ -n "$rolling_tag" ] && [ "$rolling_tag" != "null" ]; then
    log "gh-release: rolling mode — tag=$rolling_tag ← $asset"
    # Create the release iff it doesn't exist yet (idempotent). Note
    # `gh release view` exits 1 when missing — that's our signal.
    if ! in_nix gh release view "$rolling_tag" >/dev/null 2>&1; then
      local create_flags=("$rolling_tag" --title "$rolling_tag" --target "${GITHUB_SHA:-main}" --notes "Rolling release — overwritten on every main push." --latest)
      [ "$draft" = "true" ]      && create_flags+=(--draft)
      [ "$prerelease" = "true" ] && create_flags+=(--prerelease)
      in_nix gh release create "${create_flags[@]}"
    fi
    # Overwrite the asset on every run.
    in_nix gh release upload "$rolling_tag" "$DIST_DIR/$asset" --clobber
    # Re-affirm the Latest flag every run — GH unmarks it when another
    # release is published (or sometimes after a sibling release is
    # deleted), and /releases/latest/ depends on it. Idempotent.
    in_nix gh release edit "$rolling_tag" --latest >/dev/null 2>&1 || true
  fi

  # ─── Per-tag immutable release (legacy mode) ────────────────────────
  # Still publish a tag-named release when invoked under a tag push, so
  # the legacy ea_cloud-superapp-vN.N.N tags keep producing pinned
  # releases. Rolling mode above runs in addition to this, not instead.
  #
  # Gate on GITHUB_REF (full "refs/tags/..." or "refs/heads/...") rather
  # than GITHUB_REF_NAME — GitHub Actions sets GITHUB_REF_NAME as a
  # default-env var the runner re-injects even when a step's env block
  # tries to clear it with an empty string, so it would always read
  # "main" on a main-branch push and falsely trigger this branch. Once
  # we know it's a tag push from GITHUB_REF, GITHUB_REF_NAME holds the
  # short tag name we want to use.
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

# ─── Sync the bundled QR manifest from the front/linktree project ──
# `assets/qrcodes/qrcodes.json` is the SOLE asset the in-app QR gallery
# reads (parsed by QrGalleryDialog.kt). It is the same JSON the linktree
# web project owns, treated as canonical source-of-truth. This command
# refreshes the bundled copy from the local front clone — declarative
# (one knob: FRONT_REPO env / default sibling path), idempotent, and
# leaves a clear diff in `git status` for review before committing.
step_sync_qrcodes() {
  local src="${FRONT_REPO:-$HOME/git/front}/a-Portals/linktree/src/typescript/qrcode/qrcodes.json"
  local dst="$SCRIPT_DIR/app/src/main/assets/qrcodes/qrcodes.json"
  [ -f "$src" ] || { errlog "sync-qrcodes: source not found: $src (set FRONT_REPO if your front clone lives elsewhere)"; exit 1; }
  mkdir -p "$(dirname "$dst")"
  cp "$src" "$dst"
  log "sync-qrcodes: $(basename "$dst") ← $(realpath --relative-to="$SCRIPT_DIR" "$src" 2>/dev/null || echo "$src") ($(wc -c < "$dst") B)"
  log "  review with: git -C $SCRIPT_DIR diff -- app/src/main/assets/qrcodes/qrcodes.json"
}

# ─── Sync the WireGuard tunnel engine from ea_net-wireguard ─────────
# Cherry-picks the embeddable `tunnel/` module of upstream
# wireguard-android (https://github.com/WireGuard/wireguard-android,
# Apache-2.0) into libs/net/. The upstream clone lives at
# ${UNIX_REPO:-$HOME/git/unix}/ea_net-wireguard/ (gitignored per the
# ea_*-*/ workspace-clone convention); this command copies a fixed
# include-list into the in-tree gradle module so CI can build the
# native libwg-go.so + libwg.so + libwg-quick.so without the sibling
# clone being present.
#
# Idempotent — rsync --delete on the destinations, so a fresh upstream
# pull → one `./build.sh sync-net` → clear `git diff` for review.
step_sync_net() {
  local upstream="${UNIX_REPO:-$HOME/git/unix}/ea_net-wireguard/tunnel"
  local dst="$SCRIPT_DIR/libs/net"
  [ -d "$upstream" ] || { errlog "sync-net: upstream not found: $upstream (clone https://github.com/WireGuard/wireguard-android.git there, or set UNIX_REPO=)"; exit 1; }
  command -v rsync >/dev/null 2>&1 || { errlog "sync-net: rsync required (in nix-shell: nix shell nixpkgs#rsync)"; exit 1; }

  mkdir -p "$dst/src/main/java" "$dst/src/main/cpp"

  # Java sources: package roots com.wireguard.{android.backend,
  # android.util, config, crypto, util}. Mirror in full EXCEPT the
  # rooted-Android backend trio:
  #   • WgQuickBackend.java — depends on the missing wireguard-tools
  #     submodule + a libwg.so we don't build.
  #   • RootShell.java      — invokes `su`; only used by WgQuickBackend.
  #   • ToolsInstaller.java — installs wg/wg-quick binaries to /system;
  #     also rooted-only.
  # SuperApp targets unrooted Android via GoBackend only.
  # Exclude rules MUST come before include rules (rsync uses
  # first-matching-rule precedence). --delete-excluded so stale copies
  # of excluded files in the destination get removed (default --delete
  # treats excluded files as protected on both sides). No local-only
  # java is kept under this tree, so wiping excluded dest paths is safe.
  rsync -a --delete --delete-excluded \
    --exclude="WgQuickBackend.java" \
    --exclude="RootShell.java" \
    --exclude="ToolsInstaller.java" \
    --include="*/" --include="*.java" --exclude="*" \
    "$upstream/src/main/java/" "$dst/src/main/java/"

  # AndroidManifest declares GoBackend\$VpnService + BIND_VPN_SERVICE.
  # Manifest-merger pulls it into the app at build time.
  cp "$upstream/src/main/AndroidManifest.xml" "$dst/src/main/AndroidManifest.xml"

  # Native build chain — only what libwg-go.so needs:
  #   libwg-go/  (Go userspace + Makefile + go.mod/go.sum)
  #   ndk-compat (compat shim the Makefile-built Go wrapper links to)
  # Excludes:
  #   wireguard-tools/ (empty upstream submodule; rooted-backend only)
  #   elf-cleaner/     (empty; post-process for libwg.so/libwg-quick.so)
  #   CMakeLists.txt   (upstream's references the missing dirs — we
  #                     ship our own slim version, kept verbatim across
  #                     resyncs, that only builds libwg-go.so).
  rsync -a --delete \
    --include="libwg-go/" --include="libwg-go/*" \
    --include="ndk-compat/" --include="ndk-compat/*" \
    --exclude="*" \
    "$upstream/tools/" "$dst/src/main/cpp/"

  log "sync-net: libs/net populated from $(realpath --relative-to="$SCRIPT_DIR" "$upstream" 2>/dev/null || echo "$upstream")"
  log "  java     : $(find "$dst/src/main/java" -name '*.java' | wc -l) file(s)"
  log "  cpp/tools: $(find "$dst/src/main/cpp" -type f | wc -l) file(s)"
  log "  review with: git -C $SCRIPT_DIR status -s -- libs/net/"
}

# REFERENCE-ONLY sync for libs:firewall. Unlike sync-net, this does NOT
# vendor or rsync any upstream code: v1's libs/firewall is original,
# clean-room code. This just materialises (or updates) the RethinkDNS
# reference clone next to the other ea_* upstream mirrors so you can
# browse and hand-cherry-pick its DNS / firestack engine into
# libs/firewall later. Registered in build.json::upstreams.firewall.
step_sync_firewall() {
  local ref="${UNIX_REPO:-$HOME/git/unix}/ea_net-rethinkdns"
  local url="https://github.com/celzero/rethink-app.git"
  command -v git >/dev/null 2>&1 || { errlog "sync-firewall: git is required"; exit 1; }
  if [ -d "$ref/.git" ]; then
    log "sync-firewall: updating RethinkDNS reference clone: $ref"
    git -C "$ref" fetch --depth 1 origin && git -C "$ref" reset --hard origin/HEAD
  else
    log "sync-firewall: cloning RethinkDNS reference (Apache-2.0): $ref"
    git clone --depth 1 "$url" "$ref"
  fi
  log "sync-firewall: REFERENCE ONLY — nothing vendored into libs/firewall (clean-room v1)."
  log "  cherry-pick targets: DNS (DoH/DoT/DNSCrypt), per-connection tracker, firestack WG proxy."
  log "  browse: $ref"
}

# Cherry-picks HeliBoard's app/src/main (github.com/Helium314/HeliBoard,
# GPL-3.0 — the whole APK is already GPL-3.0) into libs/keyboard/ as the
# self-contained keyboard provider. Upstream clone lives at
# ${UNIX_REPO:-$HOME/git/unix}/ea_keyboard-heliboard/ (gitignored sibling,
# ea_*-* workspace-clone convention).
#
# Copies app/src/main VERBATIM (java/kotlin + res + assets + jni + the
# AndroidManifest.xml). The manifest is taken as-is — it is rich (own App
# class, LatinIME IME service, spellchecker service, content providers with
# @string authorities, boot receivers, <queries>) and changes between
# releases, so vendoring it verbatim keeps libs:keyboard a zero-drift mirror
# of upstream. All Cloud-SuperApp-specific reconciliation lives in the APP
# manifest (app/src/main/AndroidManifest.xml) as explicit `tools:` overlays:
#   • tools:replace="android:name" on <application> — the SuperApp's own App
#     class wins the merge over helium314.keyboard.latin.App.
#   • tools:node="remove" on the LAUNCHER intent-filter of HeliBoard's
#     SettingsActivity + the MAIN filter of SpellCheckerSettingsActivity — so
#     the keyboard adds NO second launcher icon (the SuperApp is the launcher).
# The glide blob libjni_latinimegoogle.so is NEVER bundled (keeps the APK
# FOSS); HeliBoard's built-in "Load gesture typing library" setting fetches it
# in-app on first run.
#
# Idempotent — rsync --delete. Fresh upstream pull -> one
# `./build.sh sync-heliboard` -> clear `git diff` for review.
step_sync_heliboard() {
  local upstream="${UNIX_REPO:-$HOME/git/unix}/ea_keyboard-heliboard/app/src/main"
  local dst="$SCRIPT_DIR/libs/keyboard"
  [ -d "$upstream" ] || { errlog "sync-heliboard: upstream not found: $upstream (clone https://github.com/Helium314/HeliBoard.git to ${UNIX_REPO:-$HOME/git/unix}/ea_keyboard-heliboard, or set UNIX_REPO=)"; exit 1; }
  command -v rsync >/dev/null 2>&1 || { errlog "sync-heliboard: rsync required (in nix-shell: nix shell nixpkgs#rsync)"; exit 1; }

  mkdir -p "$dst/src/main"

  # Whole app/src/main, manifest included — verbatim upstream mirror.
  rsync -a --delete "$upstream/" "$dst/src/main/"

  # Re-apply SuperApp patches (the rsync --delete above clobbers any local
  # edit to the mirror). These inject SuperApp-only features the verbatim
  # mirror can't carry — e.g. patches/0001-translate-toolbar.patch wires the
  # TRANSLATE toolbar key into the keyboard (calls libs:translate ML Kit).
  # Patch paths are relative to this service dir, so apply via git -C SCRIPT_DIR.
  if [ -d "$dst/patches" ]; then
    local p applied=0
    for p in "$dst"/patches/*.patch; do
      [ -f "$p" ] || continue
      if git -C "$SCRIPT_DIR" apply --3way "$p" 2>/dev/null \
         || patch -p1 -d "$SCRIPT_DIR" <"$p" >/dev/null 2>&1; then
        applied=$((applied+1))
      else
        errlog "sync-heliboard: FAILED to apply $(basename "$p") — upstream drift; reconcile by hand"; exit 1
      fi
    done
    log "sync-heliboard: re-applied $applied SuperApp patch(es) after mirror"
  fi

  # Rebrand the curated user-facing display strings (HeliBoard → Cloud Keyboard
  # (HeliBoard)). The rsync mirror above ships upstream's labels verbatim, so
  # this re-runs every sync. Narrow + idempotent — see step_brand_rename.
  step_brand_rename

  log "sync-heliboard: libs/keyboard populated from $(realpath --relative-to="$SCRIPT_DIR" "$upstream" 2>/dev/null || echo "$upstream")"
  log "  sources : $(find "$dst/src/main" \( -name '*.java' -o -name '*.kt' \) 2>/dev/null | wc -l) file(s)"
  log "  jni     : $(find "$dst/src/main/jni" -type f 2>/dev/null | wc -l) file(s)"
  log "  manifest: VERBATIM upstream (reconciled by app/ tools: overlays — see libs/keyboard/README.md)"
  log "  review with: git -C $SCRIPT_DIR status -s -- libs/keyboard/"
}

# Rebrand the vendored keyboard's USER-FACING display strings only:
# build.json::keyboard_brand_rename.token -> .replacement, inside ONLY the
# curated .string_keys, across every libs/keyboard values*/strings.xml.
# DATA-DRIVEN (no hardcoded key list), awk-based (FIRE rule: never sed),
# idempotent (skips lines already carrying the replacement). Deliberately
# NARROW — never touches package ids (helium314.*) or upstream attribution/
# license/URL text. Invoked at the end of sync-heliboard; also standalone.
step_brand_rename() {
  local bj="$SCRIPT_DIR/build.json"
  local resdir="$SCRIPT_DIR/libs/keyboard/src/main/res"
  command -v jq >/dev/null 2>&1 || { errlog "brand-rename: jq required"; exit 1; }
  [ -d "$resdir" ] || { errlog "brand-rename: $resdir missing — run sync-heliboard first"; exit 1; }

  local token rep
  token=$(jq -r '.keyboard_brand_rename.token' "$bj")
  rep=$(jq -r '.keyboard_brand_rename.replacement' "$bj")
  [ -n "$token" ] && [ "$token" != "null" ] || { errlog "brand-rename: no token in build.json"; exit 1; }

  local changed=0 f key
  while IFS= read -r f; do
    while IFS= read -r key; do
      [ -n "$key" ] || continue
      awk -v key="$key" -v tok="$token" -v rep="$rep" '
        index($0, "name=\"" key "\"") > 0 && index($0, rep) == 0 && index($0, tok) > 0 {
          line=$0; out="";
          while ((p=index(line,tok))>0) { out=out substr(line,1,p-1) rep; line=substr(line,p+length(tok)) }
          print out line; next
        }
        { print }
      ' "$f" > "$f.brand.tmp" && mv "$f.brand.tmp" "$f"
    done < <(jq -r '.keyboard_brand_rename.string_keys[]' "$bj")
    grep -q "$rep" "$f" 2>/dev/null && changed=$((changed+1))
  done < <(find "$resdir" -path '*/values*/strings.xml' 2>/dev/null)

  log "brand-rename: '$token' → '$rep' applied to curated keys across $changed strings.xml file(s)"
}

# Vendor the status-bar Line 0 animated pets from KartikLabhshetwar/zoomies
# (sprites CC BY-ND 4.0 — used UNMODIFIED + credited). DATA-DRIVEN: reads which
# animal/variant each tool uses from build.json::status_pets.tools and copies
# exactly those 4-gait GIFs into app/src/main/assets/zoomies/. Adding/retuning
# a pet = edit build.json + re-run this; no hardcoded animal list in the engine.
# Upstream clone lives at ${UNIX_REPO:-$HOME/git/unix}/ea_zoomies-pets/
# (gitignored sibling, ea_*-* convention).
step_sync_zoomies() {
  local upstream="${UNIX_REPO:-$HOME/git/unix}/ea_zoomies-pets/Sources/Zoomies/Pets"
  local dst="$SCRIPT_DIR/app/src/main/assets/zoomies"
  local bj="$SCRIPT_DIR/build.json"
  [ -d "$upstream" ] || { errlog "sync-zoomies: upstream not found: $upstream (clone https://github.com/KartikLabhshetwar/zoomies to ${UNIX_REPO:-$HOME/git/unix}/ea_zoomies-pets, or set UNIX_REPO=)"; exit 1; }
  command -v jq >/dev/null 2>&1 || { errlog "sync-zoomies: jq required"; exit 1; }

  rm -rf "$dst"; mkdir -p "$dst"
  local gaits; gaits=$(jq -r '.status_pets.gaits[]' "$bj")
  local n=0 miss=0
  # Unique animal/variant pairs referenced by the data — copy only those.
  while IFS=' ' read -r animal variant; do
    [ -n "$animal" ] || continue
    mkdir -p "$dst/$animal"
    local got=0
    for g in $gaits; do
      local src="$upstream/$animal/${variant}_${g}.gif"
      if [ -f "$src" ]; then
        cp -f "$src" "$dst/$animal/${variant}_${g}.gif"; n=$((n+1)); got=$((got+1))
      else
        # Not fatal — some animals ship fewer gaits (e.g. skeleton has no
        # walk_fast). The renderer (PetStrengthView.resolvePath) falls back to
        # an available gait, and bool pets only ever use idle/run.
        log "sync-zoomies: note — no '${g}' gait for $animal/$variant (renderer falls back)"
      fi
    done
    [ "$got" -gt 0 ] || { errlog "sync-zoomies: $animal/$variant has NO gait GIFs (unknown animal/variant)"; miss=$((miss+1)); }
  done < <(jq -r '.status_pets.tools | to_entries[] | "\(.value.animal) \(.value.variant)"' "$bj" | sort -u)

  # Attribution — CC BY-ND requires credit. Ship the upstream CREDITS verbatim.
  [ -f "$upstream/CREDITS.txt" ] && cp -f "$upstream/CREDITS.txt" "$dst/CREDITS.txt"

  log "sync-zoomies: $n GIF(s) → app/src/main/assets/zoomies/ ($miss missing)"
  log "  review with: git -C $SCRIPT_DIR status -s -- app/src/main/assets/zoomies/"
  [ "$miss" -eq 0 ] || { errlog "sync-zoomies: $miss unknown animal/variant pair(s) — fix build.json::status_pets or the clone"; exit 1; }
}

# Vendor keyboard dictionaries into libs/keyboard/src/main/assets/dicts/ (HeliBoard's
# bundled-dictionary folder). HeliBoard's mirror already ships main_<locale>.dict
# (word suggestions); this ADDS emoji_<locale>.dict (emoji search) — and any other
# data-driven type — so each language has full features. DATA-DRIVEN from
# build.json::keyboard_dicts (locales[] × types[].{type,dir}). ADDITIVE (no --delete):
# composes with the HeliBoard mirror, so run AFTER sync-heliboard (which rsync
# --delete's the mirror and would otherwise drop these). Upstream clone:
# ${UNIX_REPO:-$HOME/git/unix}/ea_keyboard-dicts (codeberg.org/Helium314/aosp-dictionaries).
step_sync_keyboard_dicts() {
  local upstream="${UNIX_REPO:-$HOME/git/unix}/ea_keyboard-dicts"
  local dst="$SCRIPT_DIR/libs/keyboard/src/main/assets/dicts"
  local bj="$SCRIPT_DIR/build.json"
  [ -d "$upstream" ] || { errlog "sync-keyboard-dicts: upstream not found: $upstream (clone https://codeberg.org/Helium314/aosp-dictionaries to ${UNIX_REPO:-$HOME/git/unix}/ea_keyboard-dicts, or set UNIX_REPO=)"; exit 1; }
  command -v jq >/dev/null 2>&1 || { errlog "sync-keyboard-dicts: jq required"; exit 1; }

  mkdir -p "$dst"
  local locales; locales=$(jq -r '.keyboard_dicts.locales[]' "$bj")
  local n=0 miss=0
  while IFS=' ' read -r type dir; do
    [ -n "$type" ] || continue
    for l in $locales; do
      local src="$upstream/$dir/${type}_${l}.dict"
      if [ -f "$src" ]; then
        cp -f "$src" "$dst/${type}_${l}.dict"; n=$((n+1))
      else
        errlog "sync-keyboard-dicts: missing ${type}_${l}.dict in $dir"; miss=$((miss+1))
      fi
    done
  done < <(jq -r '.keyboard_dicts.types[] | "\(.type) \(.dir)"' "$bj")

  log "sync-keyboard-dicts: $n dict(s) → libs/keyboard/.../assets/dicts/ ($miss missing). ADDITIVE — run AFTER sync-heliboard."
  log "  review with: git -C $SCRIPT_DIR status -s -- libs/keyboard/src/main/assets/dicts/"
  [ "$miss" -eq 0 ] || { errlog "sync-keyboard-dicts: $miss dict(s) missing — fix build.json::keyboard_dicts or update the clone"; exit 1; }
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
  sync-qrcodes) step_sync_qrcodes ;;
  sync-net)     step_sync_net ;;
  sync-heliboard) step_sync_heliboard ;;
  sync-firewall) step_sync_firewall ;;
  brand-rename) step_brand_rename ;;
  sync-zoomies) step_sync_zoomies ;;
  sync-keyboard-dicts) step_sync_keyboard_dicts ;;
  help|*)
    sed -n '2,/^set -euo/p' "$0" | sed 's/^# *//; /^set/d; /^$/d'
    ;;
esac
