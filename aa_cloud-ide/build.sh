#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Cloud-IDE — Universal Build Dispatcher                            ║
# ║                                                                  ║
# ║ Constellation of forked developer APKs + a thin hub APK. The hub  ║
# ║ is an in-tree gradle module; the three forks are pinned-upstream  ║
# ║ + patch-series, materialized into gitignored tracker clones.      ║
# ║ All toolchain comes from flake.nix — never assume host has it.    ║
# ║                                                                  ║
# ║ Commands:                                                        ║
# ║   build            gradle :hub:assembleDebug → dist/<artifact>    ║
# ║   release          gradle :hub:assembleRelease                    ║
# ║   dev              install hub on connected device (adb)          ║
# ║   test             gradle :hub:test (JVM unit)                    ║
# ║   instrument       gradle :hub:connectedAndroidTest (needs device)║
# ║   lint             gradle :hub:lint                               ║
# ║   clean            gradle clean + rm -rf dist/                    ║
# ║   shell            enter Nix devShell                            ║
# ║   ship             build + side-load hub via adb                 ║
# ║   verify-contract  validate contract/ide-ipc-v1.json vs schema    ║
# ║   materialize-fork <key>  clone upstream@pin → tracker + patches  ║
# ║   build-fork <key>        gradle/cordova build in a materialized fork ║
# ║   oras-push / oras-pull / phone-install   GHCR distribution (hub) ║
# ║   gh-release       attach hub APK to a GitHub Release (rolling)   ║
# ║                                                                  ║
# ║ NEVER bypass this script for build operations.                    ║
# ╚══════════════════════════════════════════════════════════════════╝
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="$SCRIPT_DIR/dist"
CMD="${1:-help}"

log()    { printf "[%s] %s\n" "$(date '+%H:%M:%S')" "$1"; }
errlog() { printf "\033[0;31m[%s] ERROR: %s\033[0m\n" "$(date '+%H:%M:%S')" "$1" >&2; }

# Every gradle call goes through `nix develop` for a reproducible toolchain.
# BYPASS_NIX=1 uses host tools (IDE / dev only — never CI).
in_nix() {
  if [ "${BYPASS_NIX:-0}" = "1" ]; then
    "$@"
  else
    command -v nix >/dev/null 2>&1 || { errlog "nix not on PATH; install nix or set BYPASS_NIX=1"; exit 1; }
    nix develop "$SCRIPT_DIR" --command "$@"
  fi
}

# Lightweight metadata tools (jq/git) don't need the Android devShell — prefer
# the host binary when present, fall back to nix.
prefer_host() {
  if command -v "$1" >/dev/null 2>&1; then "$@"; else in_nix "$@"; fi
}

_json() { prefer_host jq -r "$1 // empty" "$SCRIPT_DIR/build.json"; }

# ── hub build/test ─────────────────────────────────────────────────────
step_build() {
  log "Build: cloud-ide hub (debug APK)"
  in_nix gradle :hub:assembleDebug
  mkdir -p "$DIST_DIR"
  local out="$DIST_DIR/$(_json '.release.artifact.debug')"
  cp "$SCRIPT_DIR/hub/build/outputs/apk/debug/hub-debug.apk" "$out"
  log "→ $out"
}

step_release() {
  log "Build: cloud-ide hub (release APK)"
  in_nix gradle :hub:assembleRelease
  mkdir -p "$DIST_DIR"
  local out="$DIST_DIR/$(_json '.release.artifact.release')"
  cp "$SCRIPT_DIR/hub/build/outputs/apk/release/hub-release.apk" "$out" 2>/dev/null \
    || cp "$SCRIPT_DIR/hub/build/outputs/apk/release/hub-release-unsigned.apk" "${out%.apk}-unsigned.apk"
  log "→ $DIST_DIR/"
}

step_dev() {
  log "Dev: installing hub on connected device (adb)"
  in_nix gradle :hub:installDebug
  in_nix adb shell am start -n "$(_json '.android.application_id')/com.diegonmarcos.ide.MainActivity"
}

step_test()       { log "Test: hub JVM unit tests"; in_nix gradle :hub:test; }
step_instrument() { log "Test: hub instrumented (needs device)"; in_nix gradle :hub:connectedAndroidTest; }
step_lint()       { log "Lint: hub"; in_nix gradle :hub:lint; }
step_clean()      { log "Clean"; in_nix gradle clean; rm -rf "$DIST_DIR"; }
step_shell()      { log "Entering Nix devShell"; exec nix develop "$SCRIPT_DIR"; }

step_ship() {
  step_build
  log "Ship: side-loading hub via adb"
  in_nix adb install -r "$DIST_DIR/$(_json '.release.artifact.debug')"
}

# ── verify-contract ────────────────────────────────────────────────────
# Validate the IPC contract against its JSON Schema, and assert build.json::ipc
# stays consistent with it. This is the Phase-0 tester gate — no scaffold is
# "done" until the contract validates (FIRE rule 5).
step_verify_contract() {
  local contract="$SCRIPT_DIR/contract/ide-ipc-v1.json"
  local schema="$SCRIPT_DIR/contract/ide-ipc-v1.schema.json"
  [ -f "$contract" ] || { errlog "missing $contract"; exit 1; }
  [ -f "$schema" ]   || { errlog "missing $schema"; exit 1; }

  log "verify-contract: schema validation"
  if command -v check-jsonschema >/dev/null 2>&1; then
    check-jsonschema --schemafile "$schema" "$contract"
  else
    in_nix check-jsonschema --schemafile "$schema" "$contract"
  fi

  log "verify-contract: build.json::ipc ↔ contract cross-check"
  local c_auth c_perm c_ver c_svc b_auth b_perm b_ver b_svc
  c_auth="$(prefer_host jq -r '.authority' "$contract")"
  c_perm="$(prefer_host jq -r '.permission' "$contract")"
  c_ver="$(prefer_host jq -r '.version' "$contract")"
  c_svc="$(prefer_host jq -r '.aidl.service_interface' "$contract")"
  b_auth="$(_json '.ipc.authority')"; b_perm="$(_json '.ipc.permission')"
  b_ver="$(_json '.ipc.version')";    b_svc="$(_json '.ipc.aidl_service')"
  local ok=1
  [ "$c_auth" = "$b_auth" ] || { errlog "authority mismatch: contract=$c_auth build.json=$b_auth"; ok=0; }
  [ "$c_perm" = "$b_perm" ] || { errlog "permission mismatch: contract=$c_perm build.json=$b_perm"; ok=0; }
  [ "$c_ver"  = "$b_ver"  ] || { errlog "version mismatch: contract=$c_ver build.json=$b_ver"; ok=0; }
  [ "$c_svc"  = "$b_svc"  ] || { errlog "aidl_service mismatch: contract=$c_svc build.json=$b_svc"; ok=0; }
  [ "$ok" = "1" ] || exit 1
  log "verify-contract: ✓ contract valid + consistent with build.json (v$c_ver)"
}

# ── materialize-fork <key> ─────────────────────────────────────────────
# Declaratively reconstruct a fork: clone the upstream at the pinned tag into
# its (gitignored) tracker dir, then apply the committed patch series. Same
# input → same working tree. NEVER produces a long-lived divergent clone.
step_materialize_fork() {
  local key="${2:-}"
  [ -n "$key" ] || { errlog "usage: build.sh materialize-fork <files|utils|editor>"; exit 1; }

  local repo tracker tag blocked
  repo="$(_json ".forks.${key}.upstream_repo")"
  tracker="$(_json ".forks.${key}.tracker_dir")"
  tag="$(_json ".forks.${key}.pinned_tag")"
  blocked="$(_json ".forks.${key}.blocked_on")"
  [ -n "$repo" ] && [ -n "$tracker" ] || { errlog "unknown fork '$key' in build.json::forks"; exit 1; }

  if [ -n "$blocked" ] && [ "$blocked" != "null" ]; then
    errlog "fork '$key' is BLOCKED on: $blocked — resolve the blocker before materializing."
    exit 1
  fi
  if [ -z "$tag" ]; then
    errlog "fork '$key' has no pinned_tag in build.json::forks.${key}.pinned_tag."
    errlog "  Pin an upstream release tag (see $repo releases) before materializing."
    exit 1
  fi

  local dest="$SCRIPT_DIR/../$tracker"
  local patch_dir="$SCRIPT_DIR/forks/${key}/patches"

  if [ ! -d "$dest/.git" ]; then
    log "materialize-fork[$key]: cloning $repo → $tracker (tag $tag)"
    prefer_host git clone --filter=blob:none "$repo" "$dest"
  fi
  log "materialize-fork[$key]: reset to pinned tag $tag"
  prefer_host git -C "$dest" fetch --tags origin
  prefer_host git -C "$dest" reset --hard "$tag"
  prefer_host git -C "$dest" clean -fdx

  # Apply the committed patch series in lexical order. Empty series = a pure
  # upstream checkout (valid during early scaffolding).
  shopt -s nullglob
  local patches=("$patch_dir"/*.patch)
  shopt -u nullglob
  if [ "${#patches[@]}" -eq 0 ]; then
    log "materialize-fork[$key]: no patches yet — left at clean upstream $tag"
  else
    log "materialize-fork[$key]: applying ${#patches[@]} patch(es)"
    local p
    for p in "${patches[@]}"; do
      log "  git am $(basename "$p")"
      prefer_host git -C "$dest" am "$p"
    done
  fi
  log "materialize-fork[$key]: ✓ $tracker ready (build with: ./build.sh build-fork $key)"
}

# ── build-fork <key> ───────────────────────────────────────────────────
# Build a materialized fork's APK. Delegates to the fork's OWN build system
# (native gradle for files/utils; Cordova's gradle for editor). Output is
# copied into dist/ alongside the hub APK.
step_build_fork() {
  local key="${2:-}"
  [ -n "$key" ] || { errlog "usage: build.sh build-fork <files|utils|editor>"; exit 1; }
  local tracker dest
  tracker="$(_json ".forks.${key}.tracker_dir")"
  dest="$SCRIPT_DIR/../$tracker"
  [ -d "$dest/.git" ] || { errlog "fork '$key' not materialized — run: ./build.sh materialize-fork $key"; exit 1; }
  log "build-fork[$key]: delegating to $tracker's own build"
  ( cd "$dest" && in_nix gradle assembleRelease )
  mkdir -p "$DIST_DIR"
  local apk
  apk="$(prefer_host find "$dest" -path '*/outputs/apk/*release*.apk' -print -quit 2>/dev/null || true)"
  [ -n "$apk" ] && cp "$apk" "$DIST_DIR/cloud-ide-${key}.apk" && log "→ $DIST_DIR/cloud-ide-${key}.apk"
}

# ── GHCR distribution (hub APK) ─────────────────────────────────────────
# Same OCI HTTP flow as ea_cloud-superapp / cloud-comms; the hub's in-app
# updater drives the on-device fleet. Registry/tags are data-driven from
# build.json::release.ghcr.
_resolve_template() {
  local tmpl="$1"
  local sha="${GITHUB_SHA:-$(prefer_host git -C "$SCRIPT_DIR" rev-parse --short=8 HEAD 2>/dev/null || echo unknown)}"
  local ver; ver="$(_json '.android.version_name')"
  echo "${tmpl//\{sha\}/${sha:0:8}}" | sed "s|{version_name}|$ver|g"
}

step_oras_push() {
  [ "$(_json '.release.ghcr.enabled')" = "true" ] || { log "oras-push: disabled — skip"; return 0; }
  local registry namespace image media_type artifact
  registry="$(_json '.release.ghcr.registry')"
  namespace="$(_json '.release.ghcr.namespace')"
  image="$(_json '.release.ghcr.image')"
  media_type="$(_json '.release.ghcr.media_type')"
  if   [ -f "$DIST_DIR/$(_json '.release.artifact.release')" ]; then artifact="$DIST_DIR/$(_json '.release.artifact.release')"
  elif [ -f "$DIST_DIR/$(_json '.release.artifact.debug')" ];   then artifact="$DIST_DIR/$(_json '.release.artifact.debug')"
  else errlog "oras-push: no APK in $DIST_DIR — run build/release first"; exit 1; fi
  local adir aname; adir="$(dirname "$artifact")"; aname="$(basename "$artifact")"
  local tmpl tag ref
  while IFS= read -r tmpl; do
    [ -z "$tmpl" ] && continue
    tag="$(_resolve_template "$tmpl")"; ref="$registry/$namespace/$image:$tag"
    log "oras push $ref ← $aname"
    ( cd "$adir" && in_nix oras push "$ref" "$aname:$media_type" --artifact-type "$media_type" )
  done < <(prefer_host jq -r '.release.ghcr.tags[]' "$SCRIPT_DIR/build.json")
}

step_oras_pull() {
  local registry namespace image tag
  registry="$(_json '.release.ghcr.registry')"
  namespace="$(_json '.release.ghcr.namespace')"
  image="$(_json '.release.ghcr.image')"
  tag="${2:-$(_json '.release.phone_install.default_tag')}"; tag="${tag:-latest}"
  local repo="$namespace/$image" token manifest digest asset_title out got_sha
  log "oras-pull: $registry/$repo:$tag (OCI HTTP API)"
  token="$(curl -sf "https://$registry/token?service=$registry&scope=repository:$repo:pull" | jq -r .token)"
  [ -n "$token" ] && [ "$token" != "null" ] || { errlog "no bearer token"; exit 1; }
  mkdir -p "$DIST_DIR"
  manifest="$(curl -sfL -H "Authorization: Bearer $token" \
    -H "Accept: application/vnd.oci.image.manifest.v1+json" \
    "https://$registry/v2/$repo/manifests/$tag")"
  digest="$(jq -r '.layers[0].digest' <<<"$manifest")"
  asset_title="$(jq -r '.layers[0].annotations["org.opencontainers.image.title"] // "cloud-ide-hub.apk"' <<<"$manifest")"
  [ -n "$digest" ] && [ "$digest" != "null" ] || { errlog "manifest has no layers"; exit 1; }
  out="$DIST_DIR/$asset_title"
  curl -sfL -H "Authorization: Bearer $token" "https://$registry/v2/$repo/blobs/$digest" -o "$out"
  got_sha="$(sha256sum "$out" | cut -d' ' -f1)"
  [ "sha256:$got_sha" = "$digest" ] || { errlog "digest mismatch"; exit 1; }
  log "  ✓ $out"
}

step_phone_install() {
  step_oras_pull "$@"
  local target_dir asset_name src
  target_dir="${PHONE_TARGET:-$(_json '.release.phone_install.target_dir')}"
  target_dir="${target_dir/#\~/$HOME}"
  asset_name="$(_json '.release.phone_install.asset_name')"
  src="$(ls -1t "$DIST_DIR"/*.apk 2>/dev/null | head -1)"
  [ -f "$src" ] || { errlog "no APK in $DIST_DIR"; exit 1; }
  [ -d "$target_dir" ] || { errlog "phone-install: $target_dir does not exist (Termux: termux-setup-storage, or set PHONE_TARGET=)"; exit 1; }
  cp "$src" "$target_dir/$asset_name"
  log "✓ $target_dir/$asset_name — open Files → Download → tap APK"
}

# ── GitHub Release (engine reads build.json::release.gh_release) ────────
# Same pattern as ea_cloud-comms / ea_cloud-superapp. Rolling mode keeps one
# release (release.gh_release.rolling_tag, default `latest`), overwriting its
# asset on every main push so /releases/latest/download/<asset> is stable; a tag
# push (refs/tags/ea_cloud-ide-v*) also cuts an immutable per-tag release.
step_gh_release() {
  [ "$(_json '.release.gh_release.enabled')" = "true" ] || { log "gh-release: disabled — skip"; return 0; }
  local draft prerelease notes asset rolling_tag src_release src_debug dst
  draft="$(_json '.release.gh_release.draft')"
  prerelease="$(_json '.release.gh_release.prerelease')"
  notes="$(_json '.release.gh_release.generate_release_notes')"
  asset="$(_resolve_template "$(_json '.release.gh_release.asset_name')")"
  rolling_tag="$(_json '.release.gh_release.rolling_tag')"

  src_release="$DIST_DIR/$(_json '.release.artifact.release')"
  src_debug="$DIST_DIR/$(_json '.release.artifact.debug')"
  dst="$DIST_DIR/$asset"
  if   [ -f "$src_release" ] && [ "$src_release" != "$dst" ]; then cp "$src_release" "$dst"
  elif [ -f "$src_debug" ]   && [ "$src_debug"   != "$dst" ]; then cp "$src_debug"   "$dst"
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
    in_nix gh release upload "$rolling_tag" "$dst" --clobber
    in_nix gh release edit "$rolling_tag" --latest >/dev/null 2>&1 || true
  fi

  # Immutable per-tag release under a tag push (refs/tags/...). Gate on
  # GITHUB_REF, not GITHUB_REF_NAME (the runner re-injects the latter as the
  # branch name on main pushes, which would falsely create a "main" release).
  local is_tag_push=0
  case "${GITHUB_REF:-}" in refs/tags/*) is_tag_push=1 ;; esac
  if [ "$is_tag_push" = "1" ] && [ -n "${GITHUB_REF_NAME:-}" ]; then
    local flags=("$GITHUB_REF_NAME" "$dst" --title "$GITHUB_REF_NAME")
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
  build)            step_build ;;
  release)          step_release ;;
  dev)              step_dev ;;
  test)             step_test ;;
  instrument)       step_instrument ;;
  lint)             step_lint ;;
  clean)            step_clean ;;
  shell)            step_shell ;;
  ship)             step_ship ;;
  verify-contract)  step_verify_contract ;;
  materialize-fork) step_materialize_fork "$@" ;;
  build-fork)       step_build_fork "$@" ;;
  oras-push)        step_oras_push ;;
  oras-pull)        step_oras_pull "$@" ;;
  phone-install)    step_phone_install "$@" ;;
  gh-release)       step_gh_release ;;
  help|*)
    sed -n '2,/^set -euo/p' "$0" | sed 's/^# *//; /^set/d; /^$/d'
    ;;
esac
