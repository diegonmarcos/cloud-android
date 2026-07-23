#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Cloud-IDE — Universal Build Dispatcher                            ║
# ║                                                                  ║
# ║ Single-WebView-app: a thin hub APK whose WebView loads the        ║
# ║ my-konsole frontend (bundled from the sibling da_my-konsole repo   ║
# ║ at build time — see step_bundle_frontend). Hub is an in-tree      ║
# ║ gradle module. All toolchain comes from flake.nix — never assume  ║
# ║ host has it.                                                     ║
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
# ║   bundle-frontend  copy da_my-konsole/frontend → hub assets       ║
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

# Resolve an ABI (explicit arg, or build.json::release.abis default) into
# "abi|artifact_suffix". Single source of truth = build.json. (The rust_target
# leg used to live here too — that was Amaze-native-fork-only plumbing for
# build-fork's -PcloudIdeRustTargets; the hub is pure-JVM so it's gone with
# the forks.)
_abi_fields() {
  local abi="${1:-}"
  [ -n "$abi" ] || abi="$(prefer_host jq -r '[.release.abis | to_entries[] | select((.key|startswith("_")|not) and (.value|type=="object") and (.value.default==true)) | .key][0] // "arm64-v8a"' "$SCRIPT_DIR/build.json")"
  local entry def
  entry="$(_json ".release.abis[\"$abi\"]")"
  [ -n "$entry" ] && [ "$entry" != "null" ] || { errlog "unknown abi '$abi' — not in build.json::release.abis"; return 1; }
  def="$(_json ".release.abis[\"$abi\"].default")"
  local suffix=""; [ "$def" = "true" ] || suffix="-$abi"
  echo "${abi}|${suffix}"
}

# ── bundle-frontend ────────────────────────────────────────────────────
# ponytail: this couples the hub build to the sibling da_my-konsole/frontend
# tree living in the SAME monorepo checkout — both check out together in CI,
# so a relative path (build.json::frontend_source) is enough; no submodule,
# no fetch, no version pin needed.
# Copies the my-konsole frontend (index.html/js/css/vendor) into the hub's
# WebView asset root so `file:///android_asset/frontend/index.html` resolves.
# Ensures xterm is vendored first (da_my-konsole/build.sh vendor) since that
# tree is gitignored/build-time-populated, not committed.
step_bundle_frontend() {
  local frontend_src; frontend_src="$(_json '.frontend_source')"
  [ -n "$frontend_src" ] || { errlog "bundle-frontend: build.json::frontend_source is empty"; exit 1; }
  local src_dir; src_dir="$(cd "$SCRIPT_DIR/$frontend_src" 2>/dev/null && pwd)" \
    || { errlog "bundle-frontend: frontend_source resolves to a missing dir: $SCRIPT_DIR/$frontend_src"; exit 1; }

  # Vendor step is idempotent + only needed once; skip it when vendor/ already
  # has assets (e.g. a prior run, or a CI cache) rather than re-running it.
  shopt -s nullglob
  local vendored=("$src_dir"/vendor/*.js)
  shopt -u nullglob
  if [ "${#vendored[@]}" -eq 0 ]; then
    log "bundle-frontend: vendor/ empty — running da_my-konsole/build.sh vendor"
    if ! ( cd "$src_dir/.." && ./build.sh vendor ); then
      log "bundle-frontend: vendor step failed/unavailable — continuing (vendor/ may already be populated some other way)"
    fi
  else
    log "bundle-frontend: vendor/ already populated (${#vendored[@]} file(s)) — skip"
  fi

  # Profiles + config → frontend/data/*.json (theme/keybindings/engine block +
  # the 12 profiles). UNconditional, unlike vendor: these change per build and
  # are gitignored/generated, so a fresh checkout has none. Skipping it ships a
  # WebView whose fetch("data/…") 404s → no profiles + no engine switcher.
  log "bundle-frontend: regenerating frontend/data/ (profiles + config)"
  if ! ( cd "$src_dir/.." && ./build.sh bundle-data ); then
    errlog "bundle-frontend: bundle-data failed — frontend/data/{profiles,config}.json would be missing"; exit 1
  fi

  local dest="$SCRIPT_DIR/hub/src/main/assets/frontend"
  rm -rf "$dest"
  mkdir -p "$dest"
  cp -r "$src_dir"/. "$dest"/
  log "bundle-frontend: ✓ $src_dir → $dest"
}

# ── signing-key resolver (build.json::signing → vault → env) ──────────
# Resolves the SHARED Cloud-constellation key from vault and exports the
# ANDROID_KEYSTORE_* env the gradle signingConfig reads, so local + CI sign
# identically (and the fleet updater can install updates). THERE IS NO
# FALLBACK: if the vault key / sops / age key isn't available the build FAILS
# LOUD (exit 1) telling you to use the one shared constellation key — it never
# generates or substitutes a random/legacy key. CI sets VAULT_DIR + SOPS_AGE_KEY.
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
  ks_rel="$(_json '.signing.vault_keystore')"
  sec_rel="$(_json '.signing.vault_secrets')"
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

# ── hub build/test ─────────────────────────────────────────────────────
step_build() {
  local abifields abi suffix
  abifields="$(_abi_fields "${2:-}")" || exit 1
  abi="${abifields%%|*}"; suffix="$(echo "$abifields" | cut -d'|' -f2)"
  step_bundle_frontend
  _resolve_signing
  log "Build: cloud-ide hub (WebView + my-konsole frontend, debug APK, abi $abi)"
  in_nix gradle :hub:assembleDebug
  mkdir -p "$DIST_DIR"
  local base out; base="$(_json '.release.artifact.debug')"
  out="$DIST_DIR/${base%.apk}${suffix}.apk"
  cp "$SCRIPT_DIR/hub/build/outputs/apk/debug/hub-debug.apk" "$out"
  _enforce_signature "$out"
  log "→ $out"
}

step_release() {
  local abifields abi suffix
  abifields="$(_abi_fields "${2:-}")" || exit 1
  abi="${abifields%%|*}"; suffix="$(echo "$abifields" | cut -d'|' -f2)"
  step_bundle_frontend
  _resolve_signing
  log "Build: cloud-ide hub (WebView + my-konsole frontend, release APK, abi $abi)"
  in_nix gradle :hub:assembleRelease
  mkdir -p "$DIST_DIR"
  local base out; base="$(_json '.release.artifact.release')"
  out="$DIST_DIR/${base%.apk}${suffix}.apk"
  cp "$SCRIPT_DIR/hub/build/outputs/apk/release/hub-release.apk" "$out" 2>/dev/null \
    || cp "$SCRIPT_DIR/hub/build/outputs/apk/release/hub-release-unsigned.apk" "${out%.apk}-unsigned.apk"
  if [ -f "$out" ]; then _enforce_signature "$out"; else _enforce_signature "${out%.apk}-unsigned.apk"; fi
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
  local registry namespace image media_type
  registry="$(_json '.release.ghcr.registry')"
  namespace="$(_json '.release.ghcr.namespace')"
  image="$(_json '.release.ghcr.image')"
  media_type="$(_json '.release.ghcr.media_type')"

  # ABI-aware publish: for EACH abi whose hub artifact exists in dist, push it
  # under the configured tags each suffixed with the abi suffix (default abi →
  # no suffix → the plain `latest`). The ABI-aware updater pulls <tag><suffix>.
  local base_debug base_release pushed=0 abi suffix art aname adir tmpl tag ref
  base_debug="$(_json '.release.artifact.debug')"      # e.g. Cloud-IDE-Hub.apk
  base_release="$(_json '.release.artifact.release')"
  while IFS= read -r abi; do
    [ -z "$abi" ] && continue
    suffix="$(_abi_fields "$abi" | cut -d'|' -f2)"
    # Prefer the release artifact, else the debug one, for this abi.
    if   [ -f "$DIST_DIR/${base_release%.apk}${suffix}.apk" ]; then art="$DIST_DIR/${base_release%.apk}${suffix}.apk"
    elif [ -f "$DIST_DIR/${base_debug%.apk}${suffix}.apk" ];   then art="$DIST_DIR/${base_debug%.apk}${suffix}.apk"
    else log "oras-push: no artifact for abi $abi (suffix '${suffix}') — skip"; continue; fi
    adir="$(dirname "$art")"; aname="$(basename "$art")"
    while IFS= read -r tmpl; do
      [ -z "$tmpl" ] && continue
      tag="$(_resolve_template "$tmpl")${suffix}"; ref="$registry/$namespace/$image:$tag"
      log "oras push $ref ← $aname"
      ( cd "$adir" && in_nix oras push "$ref" "$aname:$media_type" --artifact-type "$media_type" )
    done < <(prefer_host jq -r '.release.ghcr.tags[]' "$SCRIPT_DIR/build.json")
    pushed=$((pushed+1))
  done < <(prefer_host jq -r '.release.abis | to_entries[] | select(.key|startswith("_")|not) | .key' "$SCRIPT_DIR/build.json")
  [ "$pushed" -ge 1 ] || { errlog "oras-push: no per-abi APK in $DIST_DIR — run build/release first"; exit 1; }
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
  build)            step_build "$@" ;;
  release)          step_release "$@" ;;
  dev)              step_dev ;;
  test)             step_test ;;
  instrument)       step_instrument ;;
  lint)             step_lint ;;
  clean)            step_clean ;;
  shell)            step_shell ;;
  ship)             step_ship ;;
  bundle-frontend)  step_bundle_frontend ;;
  oras-push)        step_oras_push ;;
  oras-pull)        step_oras_pull "$@" ;;
  phone-install)    step_phone_install "$@" ;;
  gh-release)       step_gh_release ;;
  help|*)
    sed -n '2,/^set -euo/p' "$0" | sed 's/^# *//; /^set/d; /^$/d'
    ;;
esac
