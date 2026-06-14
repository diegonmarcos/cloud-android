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
# local builds + CI produce a byte-identical signature. Graceful no-op
# (gradle then falls back to the legacy debug keystore) when the vault
# key / sops / age key isn't available — keeps pure-local dev working.
# CI sets VAULT_DIR (vault checkout) + SOPS_AGE_KEY so it resolves there too.
_resolve_signing() {
  local ks_rel sec_rel vault ks store_pw key_pw alias_
  ks_rel="$(_release_var '.signing.vault_keystore')"
  sec_rel="$(_release_var '.signing.vault_secrets')"
  [ -n "$ks_rel" ] || return 0
  vault="${VAULT_DIR:-$HOME/git/vault}"
  ks="$vault/$ks_rel"
  if [ ! -f "$ks" ]; then
    errlog "signing: shared keystore not at $ks — using legacy debug keystore (local/CI signatures will differ)"
    return 0
  fi
  command -v sops >/dev/null 2>&1 || { errlog "signing: sops not on PATH — using legacy debug keystore"; return 0; }
  store_pw="$(sops -d --extract '["keystore_password"]' "$vault/$sec_rel" 2>/dev/null || true)"
  key_pw="$(sops   -d --extract '["key_password"]'      "$vault/$sec_rel" 2>/dev/null || true)"
  alias_="$(sops   -d --extract '["key_alias"]'         "$vault/$sec_rel" 2>/dev/null || true)"
  if [ -z "$store_pw" ] || [ -z "$alias_" ]; then
    errlog "signing: cannot decrypt $sec_rel (need SOPS_AGE_KEY[_FILE]) — using legacy debug keystore"
    return 0
  fi
  export ANDROID_KEYSTORE_FILE="$ks"
  export ANDROID_KEYSTORE_PASSWORD="$store_pw"
  export ANDROID_KEY_PASSWORD="$key_pw"
  export ANDROID_KEY_ALIAS="$alias_"
  log "signing: shared constellation key (alias $alias_) from vault"
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
    log "oras push $ref ← $artifact_name"
    ( cd "$artifact_dir" && in_nix oras push "$ref" "$artifact_name:$media_type" \
        --artifact-type "$media_type" )
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

# ── step_refresh_tree ─────────────────────────────────────────────────
# Snapshot the L3 folder topology of every ea_* sibling clone under
# ~/git/unix/ and write to data/folder-tree.txt. Re-run whenever the
# topology of an upstream changes (or whenever a new sibling gets
# cloned). The file is read by app/build.gradle at config time and
# baked into BuildConfig so the Stack section in About can render it
# even when GHA builds the APK (where ea_* siblings aren't present —
# they're gitignored).
step_refresh_tree() {
  local unix_root="${UNIX_REPO:-$HOME/git/unix}"
  local out="$SCRIPT_DIR/data/folder-tree.txt"
  [ -d "$unix_root" ] || { errlog "refresh-tree: $unix_root not found"; exit 1; }
  mkdir -p "$(dirname "$out")"

  {
    echo "${unix_root##*/}/"
    # Find all ea_* sibling dirs at depth 1, sorted.
    local sibs=()
    while IFS= read -r d; do sibs+=("$d"); done < <(
      find "$unix_root" -maxdepth 1 -type d -name 'ea_*' -printf '%f\n' | sort
    )
    local n="${#sibs[@]}"
    local i=0
    for sib in "${sibs[@]}"; do
      i=$((i + 1))
      local last1="false"; [ "$i" = "$n" ] && last1="true"
      local head1="├── "; [ "$last1" = "true" ] && head1="└── "
      echo "${head1}${sib}/"
      # Depth-2 children of this sibling, skipping dotfiles + common
      # build/output dirs.
      local children=()
      # Skip the parent's own name as a child — guards against the
      # accidental doubly-nested ea_cloud-superapp/ea_cloud-superapp/
      # (empty leftover from an old sync experiment) showing up here.
      while IFS= read -r c; do children+=("$c"); done < <(
        find "$unix_root/$sib" -mindepth 1 -maxdepth 1 -type d \
          ! -name '.*' ! -name 'build' ! -name 'dist' ! -name 'node_modules' \
          ! -name '.gradle' ! -name '.result' \
          ! -name "$sib" -printf '%f\n' | sort
      )
      local m="${#children[@]}"
      local j=0
      local prefix2="│   "; [ "$last1" = "true" ] && prefix2="    "
      for c in "${children[@]}"; do
        j=$((j + 1))
        local last2="false"; [ "$j" = "$m" ] && last2="true"
        local head2="├── "; [ "$last2" = "true" ] && head2="└── "
        echo "${prefix2}${head2}${c}/"
        # L3 grand-children — direct sub-dirs of this depth-2 dir.
        # Skip the same set of build/output dirs as depth-2.
        local grand=()
        while IFS= read -r g; do grand+=("$g"); done < <(
          find "$unix_root/$sib/$c" -mindepth 1 -maxdepth 1 -type d \
            ! -name '.*' ! -name 'build' ! -name 'dist' ! -name 'node_modules' \
            ! -name '.gradle' ! -name '.result' -printf '%f\n' | sort
        )
        local p="${#grand[@]}"
        local k=0
        local prefix3="${prefix2}│   "; [ "$last2" = "true" ] && prefix3="${prefix2}    "
        for g in "${grand[@]}"; do
          k=$((k + 1))
          local head3="├── "; [ "$k" = "$p" ] && head3="└── "
          echo "${prefix3}${head3}${g}/"
        done
      done
    done
  } > "$out"

  log "refresh-tree: wrote $out ($(wc -l < "$out") lines)"
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
  refresh-tree) step_refresh_tree ;;
  help|*)
    sed -n '2,/^set -euo/p' "$0" | sed 's/^# *//; /^set/d; /^$/d'
    ;;
esac
