# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-nix-on-droid-fork-engine.sh ───
#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Cloud Nix-on-Droid — Build Dispatcher                                    ║
# ║ Constellation of forked comms APKs + a thin hub APK. The hub is   ║
# ║ an in-tree gradle module; the three forks are pinned-upstream +   ║
# ║ patch-series, materialized into gitignored tracker clones.        ║
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
# ║   verify-contract  validate contract/comms-ipc-v1.json vs schema  ║
# ║   bundle-forks     embed published fork APKs into hub assets/      ║
# ║   materialize-fork <key>  clone upstream@pin → tracker + patches  ║
# ║   build-fork <key>        fork's own gradlew + constellation sign ║
# ║   publish-fork <key>      oras push fork APK → ghcr fork image    ║
# ║   oras-push / oras-pull / phone-install   GHCR distribution (hub) ║
# ║   gh-release       publish hub APK to a rolling GitHub Release    ║
# ║   gh-release-fork <key>   fork APK → same rolling GitHub Release   ║
# ║                                                                  ║
# ║ NEVER bypass this script for build operations.                    ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# GENERAL-PURPOSE NOTE (2026-07-26): this engine now serves TWO app shapes,
# selected purely from build.json data (never from app name):
#   - hub+forks constellations (mail/chat/dialer/matrix): build.json has NO
#     top-level "mode" key → all steps take the original hub-coupled path
#     (gradle :hub:*, hub/build/outputs/apk/..., bundle-forks, verify-contract)
#     unchanged.
#   - hub-less single-fork apps (media-center, and future ones): build.json
#     sets top-level "mode": "single-app". The single entry under
#     build.json::forks.<key> is built directly via the existing
#     step_build_fork machinery — no hub module, no bundle-forks, no IPC
#     contract.
#
# SPLIT (2026-07-30): this used to be one shared cloud-comms-fork-engine.sh
# symlinked from all 4 ac_cloud-* build.sh entrypoints. Per-app copies now —
# no more consolidated filename. Logic is identical across the 4 copies
# (still 100% data-driven from each app's own build.json); only this
# header + the invoking symlink differ.
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

# Fork-scoped read. ALWAYS use this rather than interpolating the fork key
# into a jq path via _json —
# a fork key containing a hyphen (e.g. "media-center") is parsed by jq as
# subtraction when interpolated into a path, yielding
#     jq: error: center/0 is not defined
# Passing the key as DATA (--arg) and indexing with brackets makes any key
# safe, and closes a shell->jq injection path.
#   _fork_json "$key" '.build.gradle_task'
#   _fork_json "$key" ''                    # the whole fork object
_fork_json() {
  local k="$1" sub="${2:-}"
  prefer_host jq -r --arg k "$k" ".forks[\$k]${sub} // empty" "$SCRIPT_DIR/build.json"
}

# ── hub-less single-fork detection ──────────────────────────────────────
# Purely data-driven: build.json::mode=="single-app" opts an app OUT of the
# hub+bundle-forks+IPC-contract model. Absent (the default for every existing
# hub-based consumer's build.json) → old hub-coupled behavior, unchanged.
_is_hubless() { [ "$(_json '.mode')" = "single-app" ]; }

# The one fork key declared under forks.<key> for a hub-less app.
_single_fork_key() { _json '.forks | keys[0]'; }

# Launcher activity is read from build.json; the historical hub literal is
# kept only as the fallback default for pre-existing hub consumers that don't
# declare it, so their behavior is byte-for-byte unchanged.
_launcher_activity() {
  local a; a="$(_json '.launcher_activity')"
  if [ -z "$a" ]; then
    local key; key="$(_single_fork_key)"
    a="$(_fork_json "$key" ".launcher_activity")"
  fi
  echo "${a:-com.diegonmarcos.comms.MainActivity}"
}

# ── bundle-forks ───────────────────────────────────────────────────────
# Embedded-installer model: seed hub/src/main/assets/forks/<domain>.apk with
# each fork's PUBLISHED GHCR apk so the single hub APK carries the forks inside
# it and installs them on first launch (BundledForkInstaller). Data-driven from
# build.json::forks. A fork that's blocked or whose image isn't published yet
# (404) is skipped — the bundle is best-effort and NEVER fails the build (forks
# ship independently; the hub bundles whatever exists at build time). The .apk
# assets are gitignored — populated at build time, never committed.
step_bundle_forks() {
  local assets="$SCRIPT_DIR/hub/src/main/assets/forks"
  mkdir -p "$assets"
  rm -f "$assets"/*.apk
  local key image blocked tag up_url up_sha up_resign
  tag="$(_json '.release.auto_update.tag')"; tag="${tag:-latest}"
  while IFS= read -r key; do
    [ -z "$key" ] && continue
    image="$(_fork_json "$key" ".image")"
    blocked="$(_fork_json "$key" ".blocked_on")"
    up_url="$(_fork_json "$key" ".upstream_apk.url")"
    up_sha="$(_fork_json "$key" ".upstream_apk.sha256")"
    up_resign="$(_fork_json "$key" ".upstream_apk.resign")"
    # Per-ABI upstream variant (COMMS_BUNDLE_ABI, default arm64-v8a — the
    # device target). x86_64 builds the Waydroid-debuggable bundle.
    local bundle_abi="${COMMS_BUNDLE_ABI:-arm64-v8a}"
    if [ "$bundle_abi" != "arm64-v8a" ]; then
      local v_url v_sha
      v_url="$(_fork_json "$key" ".upstream_apk.abi_variants[\"$bundle_abi\"].url")"
      v_sha="$(_fork_json "$key" ".upstream_apk.abi_variants[\"$bundle_abi\"].sha256")"
      if [ -n "$v_url" ]; then
        up_url="$v_url"; up_sha="$v_sha"
        log "bundle-forks: $key using $bundle_abi upstream variant"
      fi
    fi

    # 1st choice: OUR fork image from GHCR (patched + constellation-signed).
    # blocked_on gates the FORK BUILD, not the bundle — a blocked fork can
    # still ship its pinned upstream release APK below.
    if { [ -z "$blocked" ] || [ "$blocked" = "null" ]; } \
       && _oci_pull_blob "$image" "$tag" "$assets/${key}.apk"; then
      _enforce_signature "$assets/${key}.apk"
      log "bundle-forks: embedded $key ← $image:$tag ($(wc -c <"$assets/${key}.apk") B)"
      continue
    fi
    rm -f "$assets/${key}.apk"

    # 2nd choice: the pinned, sha256-verified UPSTREAM release APK (owner
    # directive 2026-06-12: ALL apps ship inside the hub NOW; patched forks
    # replace these as their CI lands). resign=true re-signs with the
    # constellation key (Mattermost publishes unsigned APKs).
    if [ -n "$up_url" ]; then
      if _fetch_upstream_apk "$key" "$up_url" "$up_sha" "$up_resign" "$assets/${key}.apk"; then
        _enforce_signature "$assets/${key}.apk"
        log "bundle-forks: embedded $key ← upstream release ($(wc -c <"$assets/${key}.apk") B)"
      else
        rm -f "$assets/${key}.apk"
        log "bundle-forks: $key upstream fetch/sign failed — skip"
      fi
      continue
    fi

    if [ -n "$blocked" ] && [ "$blocked" != "null" ]; then
      log "bundle-forks: $key blocked ($blocked), no upstream_apk — skip"
    else
      log "bundle-forks: $key not published yet ($image:$tag) — skip"
    fi
  done < <(prefer_host jq -r '.forks | to_entries[] | select(.key|startswith("_")|not) | .key' "$SCRIPT_DIR/build.json")
  # Count embedded apks glob-safely — `ls *.apk | wc -l` trips set -o pipefail
  # when the glob matches nothing (empty bundle = the normal pre-fork state).
  shopt -s nullglob
  local apks=("$assets"/*.apk)
  shopt -u nullglob
  log "bundle-forks: ${#apks[@]} fork(s) embedded in the hub bundle"
}

# ── signing-key resolver (build.json::signing → vault → env) ──────────
# Resolves the SHARED Cloud-constellation key from vault and exports the
# ANDROID_KEYSTORE_* env the gradle signingConfig reads, so local + CI sign
# identically (and the fleet updater can install updates). Graceful no-op
# (gradle falls back to the legacy comms keystore) when the vault key / sops
# / age key isn't available. CI sets VAULT_DIR + SOPS_AGE_KEY.
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

# Guard against shipping an APK with the wrong package identity.
# $1=key, $2=apk path, $3=expected package id (defaults to .forks.<key>.app_id).
# Source-built forks pass app_id; upstream-APK forks pass upstream_apk.package when set.
_assert_apk_identity() {
  local key="$1" apk="$2" expected_id="${3:-}"
  if [ -z "$expected_id" ]; then
    expected_id="$(_fork_json "$key" ".app_id")"
  fi
  [ -n "$expected_id" ] && [ "$expected_id" != "null" ] \
    || { errlog "identity-assert[$key]: expected package id not provided and .forks.${key}.app_id missing"; exit 1; }
  local pkgs; pkgs="$(unzip -p "$apk" AndroidManifest.xml | LC_ALL=C strings -e l)" \
    || { errlog "identity-assert[$key]: failed to extract AndroidManifest.xml from $apk"; exit 1; }
  if ! printf '%s\n' "$pkgs" | grep -Fqx "$expected_id"; then
    errlog "identity-assert[$key]: APK package != $expected_id — refusing to publish"
    # Only report PACKAGE-SHAPED strings. Grepping for any dotted string picks
    # up intent-filter pathPatterns (ReFra registers e.g. '.*\..*\..*\.apng'
    # for file associations), which made GHA 30540989541 report
    # "Found packages: .*\..*\..*\.apng .*\..*\..*\.jxl" — useless for
    # diagnosing an identity mismatch. Application ids are lowercase dotted
    # segments with no regex metacharacters.
    errlog "  Package-shaped strings found: $(printf '%s\n' "$pkgs" \
      | grep -E '^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$' | sort -u | head -5 | tr '\n' ' ')"
    exit 1
  fi
  log "identity-assert[$key]: OK — package $expected_id confirmed in manifest"
}

# ── hub build/test ─────────────────────────────────────────────────────
# Hub-less single-fork apps (build.json::mode=="single-app") reuse the
# existing, already-generic step_build_fork machinery for their one
# forks.<key> entry — no hub module, no bundle-forks, no embedded APKs.
_step_build_single_app() {
  local variant="$1" key; key="$(_single_fork_key)"
  [ -n "$key" ] || { errlog "single-app mode but build.json::forks has no entries"; exit 1; }
  log "Build: hub-less single-fork app ($key, $variant)"
  step_build_fork "" "$key"
  mkdir -p "$DIST_DIR"
  local out="$DIST_DIR/$(_json ".release.artifact.${variant}")"
  [ "$DIST_DIR/cloud-nixdroid.apk" = "$out" ] || cp "$DIST_DIR/cloud-nixdroid.apk" "$out"
  log "→ $out"
}

step_build() {
  if _is_hubless; then _step_build_single_app debug; return; fi
  step_bundle_forks
  _resolve_signing
  log "Build: cloud-comms bundle (hub + embedded forks, debug APK)"
  in_nix gradle :hub:assembleDebug
  mkdir -p "$DIST_DIR"
  local out="$DIST_DIR/$(_json '.release.artifact.debug')"
  cp "$SCRIPT_DIR/hub/build/outputs/apk/debug/hub-debug.apk" "$out"
  _enforce_signature "$out"
  log "→ $out"
}

step_release() {
  if _is_hubless; then _step_build_single_app release; return; fi
  step_bundle_forks
  _resolve_signing
  log "Build: cloud-comms bundle (hub + embedded forks, release APK)"
  in_nix gradle :hub:assembleRelease
  mkdir -p "$DIST_DIR"
  local out="$DIST_DIR/$(_json '.release.artifact.release')"
  cp "$SCRIPT_DIR/hub/build/outputs/apk/release/hub-release.apk" "$out" 2>/dev/null \
    || cp "$SCRIPT_DIR/hub/build/outputs/apk/release/hub-release-unsigned.apk" "${out%.apk}-unsigned.apk"
  if [ -f "$out" ]; then _enforce_signature "$out"; else _enforce_signature "${out%.apk}-unsigned.apk"; fi
  log "→ $DIST_DIR/"
}

step_dev() {
  if _is_hubless; then
    local key; key="$(_single_fork_key)"
    step_build
    log "Dev: installing $key on connected device (adb)"
    in_nix adb install -r "$DIST_DIR/$(_json '.release.artifact.debug')"
    in_nix adb shell am start -n "$(_json '.android.application_id')/$(_launcher_activity)"
    return
  fi
  log "Dev: installing hub on connected device (adb)"
  in_nix gradle :hub:installDebug
  in_nix adb shell am start -n "$(_json '.android.application_id')/$(_launcher_activity)"
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
  if _is_hubless; then
    log "verify-contract: hub-less single-app mode (build.json::mode=single-app) — no IPC contract, skipping"
    return 0
  fi
  local contract="$SCRIPT_DIR/contract/comms-ipc-v1.json"
  local schema="$SCRIPT_DIR/contract/comms-ipc-v1.schema.json"
  [ -f "$contract" ] || { errlog "missing $contract"; exit 1; }
  [ -f "$schema" ]   || { errlog "missing $schema"; exit 1; }

  log "verify-contract: schema validation"
  if command -v check-jsonschema >/dev/null 2>&1; then
    check-jsonschema --schemafile "$schema" "$contract"
  else
    in_nix check-jsonschema --schemafile "$schema" "$contract"
  fi

  log "verify-contract: build.json::ipc ↔ contract cross-check"
  local c_auth c_perm c_ver c_svc c_act b_auth b_perm b_ver b_svc b_act
  c_auth="$(prefer_host jq -r '.authority' "$contract")"
  c_perm="$(prefer_host jq -r '.permission' "$contract")"
  c_ver="$(prefer_host jq -r '.version' "$contract")"
  c_svc="$(prefer_host jq -r '.aidl.service_interface' "$contract")"
  c_act="$(prefer_host jq -r '.launch_action' "$contract")"
  b_auth="$(_json '.ipc.authority')"; b_perm="$(_json '.ipc.permission')"
  b_ver="$(_json '.ipc.version')";    b_svc="$(_json '.ipc.aidl_service')"
  b_act="$(_json '.ipc.launch_action')"
  local ok=1
  [ "$c_auth" = "$b_auth" ] || { errlog "authority mismatch: contract=$c_auth build.json=$b_auth"; ok=0; }
  [ "$c_perm" = "$b_perm" ] || { errlog "permission mismatch: contract=$c_perm build.json=$b_perm"; ok=0; }
  [ "$c_ver"  = "$b_ver"  ] || { errlog "version mismatch: contract=$c_ver build.json=$b_ver"; ok=0; }
  [ "$c_svc"  = "$b_svc"  ] || { errlog "aidl_service mismatch: contract=$c_svc build.json=$b_svc"; ok=0; }
  [ "$c_act"  = "$b_act"  ] || { errlog "launch_action mismatch: contract=$c_act build.json=$b_act"; ok=0; }
  [ "$ok" = "1" ] || exit 1
  log "verify-contract: ✓ contract valid + consistent with build.json (v$c_ver)"
}

# ── materialize-fork <key> ─────────────────────────────────────────────
# Declaratively reconstruct a fork: clone the upstream at the pinned tag into
# its (gitignored) tracker dir, then apply the committed patch series. Same
# input → same working tree. NEVER produces a long-lived divergent clone.
step_materialize_fork() {
  local key="${2:-}"
  [ -n "$key" ] || { errlog "usage: build.sh materialize-fork <mail|chat|matrix>"; exit 1; }

  local repo tracker tag blocked mtask
  repo="$(_fork_json "$key" ".upstream_repo")"
  tracker="$(_fork_json "$key" ".tracker_dir")"
  tag="$(_fork_json "$key" ".pinned_tag")"
  blocked="$(_fork_json "$key" ".blocked_on")"
  [ -n "$repo" ] && [ -n "$tracker" ] || { errlog "unknown fork '$key' in build.json::forks"; exit 1; }

  # Upstream-APK forks (no gradle_task AND no build.command) build from the
  # pinned release APK, not source — nothing to clone/patch. build-fork fetches +
  # resigns it directly. A fork with EITHER a gradle_task OR a build.command
  # (RN wrapper) is a from-source fork → clone + patch below.
  mtask="$(_fork_json "$key" ".build.gradle_task")"
  local mcmd; mcmd="$(_fork_json "$key" ".build.command")"
  if { [ -z "$mtask" ] || [ "$mtask" = "null" ]; } && { [ -z "$mcmd" ] || [ "$mcmd" = "null" ]; }; then
    log "materialize-fork[$key]: upstream-APK fork (no gradle build) — no source to materialize; build-fork resigns the pinned upstream APK."
    return 0
  fi

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
  # Per-app model: patches always live beside the build.sh/engine symlink
  # (ac_cloud-<fork>/patches/). SCRIPT_DIR resolves to the invocation dir.
  local patch_dir="$SCRIPT_DIR/patches"

  # Vendored-in-repo fork (2026-08-19): tracker_dir points at a committed,
  # already-fully-patched plain source tree (no .git inside it) instead of a
  # gitignored external clone. No network clone, no git am — build-fork reads
  # straight from here. patches/ stays as historical record only; bump
  # pinned_tag and delete $dest to force the clone+patch path below and
  # re-vendor a newer upstream tag.
  if [ -d "$dest" ] && [ ! -d "$dest/.git" ]; then
    log "materialize-fork[$key]: using vendored in-repo source at $tracker (no clone, no git am — see $patch_dir for historical patch series)"
    return 0
  fi

  if [ ! -d "$dest/.git" ]; then
    log "materialize-fork[$key]: cloning $repo → $tracker (tag $tag)"
    # tracker_dir may be nested (ac_upstreams-sources/<name> — the canonical
    # upstream home since 2026-06-12); the parent is gitignored workspace and
    # won't exist on a fresh CI checkout.
    mkdir -p "$(dirname "$dest")"
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
      # Explicit ident: CI runners have no git identity (run 27418737089
      # "empty ident name"); the applied commits are reproducible-engine
      # output, not authored work.
      prefer_host git -C "$dest" \
        -c user.name="cloud-nixdroid-engine" \
        -c user.email="engine@diegonmarcos.com" \
        am "$p"
    done
  fi
  log "materialize-fork[$key]: ✓ $tracker ready (build with: ./build.sh build-fork $key)"
}

# ── build-fork <key> ───────────────────────────────────────────────────
# Build a materialized fork's APK with the fork's OWN gradle wrapper (each
# upstream pins its own Gradle/AGP — never our devShell gradle). Everything is
# data-driven from build.json::forks.<key>.build:
#   gradle_task — the exact variant task (e.g. assembleGithubRelease)
#   apk_glob    — where the output APK lands inside the tracker
#   signing     — 'keystore_properties' writes keystore.properties at the fork
#                 root from the ONE shared constellation key so the upstream's
#                 own signingConfigs.release picks it up (no signing patch needed).
# Keystore resolution: _resolve_signing exports the vault key env
# (ANDROID_KEYSTORE_*) from vault/A0_keys/providers/android/release.jks — the
# SAME key every constellation APK signs with (signature IPC + updater install
# chain). NO legacy/random fallback: _resolve_signing fails loud if absent.
step_build_fork() {
  local key="${2:-}"
  [ -n "$key" ] || { errlog "usage: build.sh build-fork <nix-on-droid>"; exit 1; }
  local tracker dest task apk_glob signing
  tracker="$(_fork_json "$key" ".tracker_dir")"
  # Gradle task may be ABI-specific. A fork whose upstream dimensions its
  # productFlavors by ABI (ReFra: flavorDimensions abi x ml) has a DIFFERENT
  # assemble task per ABI, so one fixed string cannot serve a multi-ABI build
  # matrix. Resolution order, all from build.json (never hardcoded):
  #   1. .build.gradle_task_by_abi["$COMMS_BUNDLE_ABI"]   (ABI-dimensioned forks)
  #   2. .build.gradle_task                                (single-task forks)
  # Existing hub consumers declare only (2), so their behavior is unchanged.
  task="$(_fork_json "$key" ".build.gradle_task_by_abi[\"${COMMS_BUNDLE_ABI:-arm64-v8a}\"]")"
  [ -n "$task" ] || task="$(_fork_json "$key" ".build.gradle_task")"
  apk_glob="$(_fork_json "$key" ".build.apk_glob")"
  signing="$(_fork_json "$key" ".build.signing")"
  dest="$SCRIPT_DIR/../$tracker"

  # ── Upstream-APK fork (no gradle_task AND no build.command): matrix (Element)
  #    ships the pinned upstream release APK re-signed with the ONE shared
  #    constellation key — same _fetch_upstream_apk the hub's bundle-forks uses,
  #    but here the resigned APK becomes cloud-nixdroid.apk so publish-fork
  #    can oras-push it as a STANDALONE GHCR image (Constellation AppStore).
  #    Needs no materialized source. ABI variant mirrors bundle-forks. A fork
  #    with a build.command (RN: chat) falls through to the from-source path.
  local bcmd_gate; bcmd_gate="$(_fork_json "$key" ".build.command")"
  if { [ -z "$task" ] || [ "$task" = "null" ]; } && { [ -z "$bcmd_gate" ] || [ "$bcmd_gate" = "null" ]; }; then
    local up_url up_sha up_resign bundle_abi v_url v_sha
    up_url="$(_fork_json "$key" ".upstream_apk.url")"
    up_sha="$(_fork_json "$key" ".upstream_apk.sha256")"
    up_resign="$(_fork_json "$key" ".upstream_apk.resign")"
    bundle_abi="${COMMS_BUNDLE_ABI:-arm64-v8a}"
    v_url="$(_fork_json "$key" ".upstream_apk.abi_variants[\"$bundle_abi\"].url")"
    v_sha="$(_fork_json "$key" ".upstream_apk.abi_variants[\"$bundle_abi\"].sha256")"
    if [ -n "$v_url" ] && [ "$v_url" != "null" ]; then up_url="$v_url"; up_sha="$v_sha"; fi
    [ -n "$up_url" ] && [ "$up_url" != "null" ] \
      || { errlog "fork '$key' has neither build.gradle_task nor upstream_apk.url"; exit 1; }
    mkdir -p "$DIST_DIR"
    _fetch_upstream_apk "$key" "$up_url" "$up_sha" "$up_resign" "$DIST_DIR/cloud-nixdroid.apk" \
      || { errlog "build-fork[$key]: upstream APK fetch/resign failed"; exit 1; }
    _enforce_signature "$DIST_DIR/cloud-nixdroid.apk"
    # upstream_apk.package: the actual package in the re-signed upstream APK (may differ
    # from app_id which is aspirational for a future source fork)
    local up_pkg; up_pkg="$(_fork_json "$key" ".upstream_apk.package")"
    if [ -z "$up_pkg" ] || [ "$up_pkg" = "null" ]; then up_pkg=""; fi
    _assert_apk_identity "$key" "$DIST_DIR/cloud-nixdroid.apk" "$up_pkg"
    log "build-fork[$key]: upstream-APK fork ($bundle_abi) → $DIST_DIR/cloud-nixdroid.apk ($(wc -c <"$DIST_DIR/cloud-nixdroid.apk") B)"
    return 0
  fi

  # A fork is ready when its source tree is present - either a CLONE (.git, the
  # pinned-tag + `git am` path) or a VENDORED in-repo tree (no .git; since
  # 2026-08-19 materialize-fork returns early for those and uses the committed
  # source as-is). Demanding .git here contradicted that and rejected every
  # vendored fork, which is what broke ship-cloud-mail and ship-cloud-dialer on
  # the day their trees were vendored.
  [ -d "$dest" ] || { errlog "fork '$key' not materialized — run: ./build.sh materialize-fork $key"; exit 1; }

  if [ "$signing" = "keystore_properties" ]; then
    # Resolve the ONE shared constellation key (fails loud if unavailable —
    # no legacy/random fallback). Upstream signingConfigs.release reads
    # rootProject keystore.properties; we write it from the resolved env.
    _resolve_signing
    printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
      "$ANDROID_KEYSTORE_FILE" "$ANDROID_KEYSTORE_PASSWORD" \
      "$ANDROID_KEY_ALIAS" "${ANDROID_KEY_PASSWORD:-$ANDROID_KEYSTORE_PASSWORD}" \
      > "$dest/keystore.properties"
    log "build-fork[$key]: keystore.properties → ONE shared constellation key"
  fi

  # Upstreams that read the keystore from a FILE AT A FIXED PATH plus env vars
  # instead of a keystore.properties. ReFra:
  #   signingConfigs.release { storeFile = file("release_key.jks")
  #                            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
  #                            keyAlias      = System.getenv("SIGNING_KEY_ALIAS")
  #                            keyPassword   = System.getenv("SIGNING_KEY_PASSWORD") }
  # storeFile is resolved at CONFIGURE time, so the file must exist before the
  # task graph is built or gradle fails with "specifies file ... which doesn't
  # exist" (GHA 30538571570). Both the destination path and the env-var NAMES
  # are data (upstreams pick their own names) — nothing here is hardcoded.
  if [ "$signing" = "vault_jks_env" ]; then
    _resolve_signing
    local ks_dest; ks_dest="$(_fork_json "$key" ".build.keystore_dest")"
    [ -n "$ks_dest" ] || { errlog "build-fork[$key]: signing=vault_jks_env requires .build.keystore_dest in build.json"; exit 1; }
    mkdir -p "$(dirname "$dest/$ks_dest")"
    cp -f "$ANDROID_KEYSTORE_FILE" "$dest/$ks_dest"
    log "build-fork[$key]: shared constellation keystore → $ks_dest"
    local sv_name sv_token
    while IFS=$'\t' read -r sv_name sv_token; do
      [ -n "$sv_name" ] || continue
      case "$sv_token" in
        store_password) export "$sv_name=$ANDROID_KEYSTORE_PASSWORD" ;;
        key_password)   export "$sv_name=${ANDROID_KEY_PASSWORD:-$ANDROID_KEYSTORE_PASSWORD}" ;;
        key_alias)      export "$sv_name=$ANDROID_KEY_ALIAS" ;;
        keystore_path)  export "$sv_name=$dest/$ks_dest" ;;
        *) errlog "build-fork[$key]: unknown signing_env token '$sv_token' for \$$sv_name (want store_password|key_password|key_alias|keystore_path)"; exit 1 ;;
      esac
      log "build-fork[$key]: exported \$$sv_name ($sv_token)"
    done < <(prefer_host jq -r --arg k "$key" '.forks[$k].build.signing_env // {} | to_entries[] | select(.key | startswith("_") | not) | "\(.key)\t\(.value)"' "$SCRIPT_DIR/build.json")
  fi

  # Data-driven PRE-BUILD steps (build.json::forks.<key>.build.prepare[]). Run
  # in the tracker with the fork's toolchain — host in CI (BYPASS_NIX=1, node/
  # ruby provisioned by the workflow), devShell locally. React-Native forks use
  # this for `npm ci` (→ patch-package postinstall) + `bundle install` (fastlane)
  # before the actual assemble. NEVER hardcode per-fork — build.json drives it.
  while IFS= read -r pcmd; do
    [ -n "$pcmd" ] || continue
    log "build-fork[$key]: prepare → $pcmd"
    ( cd "$dest" && in_nix bash -lc "$pcmd" ) || { errlog "build-fork[$key]: prepare failed: $pcmd"; exit 1; }
  done < <(prefer_host jq -r --arg k "$key" '.forks[$k].build.prepare // [] | .[]' "$SCRIPT_DIR/build.json")

  # Data-driven gradle -P properties from build.json::forks.<key>.build.gradle_props
  # (object key→value). The fork's build.gradle(.kts) reads these via
  # project.findProperty(...) — e.g. APPLICATION_ID (unique package id) + the
  # self-contained updater's GHCR/auto-update BuildConfig fields. NEVER hardcode
  # these here — a new key in build.json flows through with no engine change.
  local -a gprops=()
  while IFS=$'\t' read -r gp_key gp_val; do
    [ -n "$gp_key" ] || continue
    # $ENV:VAR_NAME → substitute from environment (dynamic CI values like BUILD_TIMESTAMP)
    if [[ "$gp_val" == '$ENV:'* ]]; then
      local env_var="${gp_val#'$ENV:'}"
      gp_val="${!env_var:-dev}"
    fi
    gprops+=("-P${gp_key}=${gp_val}")
  done < <(prefer_host jq -r --arg k "$key" '.forks[$k].build.gradle_props // {} | to_entries[] | select(.key | startswith("_") | not) | "\(.key)\t\(.value)"' "$SCRIPT_DIR/build.json")

  # Build command is data-driven: build.command overrides the default gradlew
  # invocation. RN forks build via their OWN wrapper (e.g.
  # `npm run build:android-unsigned` → fastlane → gradle assembleUnsigned); the
  # default keeps the fork's gradle wrapper + -P props (mail/dialer unchanged).
  local bcmd; bcmd="$(_fork_json "$key" ".build.command")"
  if [ -n "$bcmd" ] && [ "$bcmd" != "null" ]; then
    log "build-fork[$key]: $tracker → $bcmd (upstream build wrapper)"
    ( cd "$dest" && in_nix bash -lc "$bcmd" )
  else
    log "build-fork[$key]: $tracker ./gradlew $task ${gprops[*]:-(no -P props)} (upstream-pinned toolchain)"
    ( cd "$dest" && chmod +x gradlew && in_nix ./gradlew --no-daemon "$task" "${gprops[@]}" )
  fi

  mkdir -p "$DIST_DIR"
  # globstar is REQUIRED for the "**" in apk_glob to cross more than one
  # directory. Without it bash treats "**" as a plain "*" (single level), so
  # app/build/outputs/apk/**/*.apk never matches AGP's real two-level layout
  # app/build/outputs/apk/<abiFlavor><ml>/<buildType>/*.apk — which is how
  # GHA 30539856374 compiled and signed an APK and then reported
  # "no APK matched". Existing single-level consumers still match, since
  # globstar's "**" also matches zero directories.
  shopt -s nullglob globstar
  local apks=("$dest"/$apk_glob)
  shopt -u nullglob globstar
  [ "${#apks[@]}" -ge 1 ] || { errlog "build-fork[$key]: no APK matched $apk_glob"; exit 1; }
  cp "${apks[0]}" "$DIST_DIR/cloud-nixdroid.apk"
  # Forks whose upstream emits an UNSIGNED apk (build.json::forks.<key>.build.
  # resign_unsigned) get resigned with the ONE shared constellation key here —
  # same key the stock-resign path uses (signature IPC + updater install chain).
  if [ "$(_fork_json "$key" ".build.resign_unsigned")" = "true" ]; then
    log "build-fork[$key]: resign unsigned build with constellation key"
    _resign_apk "$DIST_DIR/cloud-nixdroid.apk" "$DIST_DIR/cloud-nixdroid.apk.signed" \
      && mv "$DIST_DIR/cloud-nixdroid.apk.signed" "$DIST_DIR/cloud-nixdroid.apk" \
      || { errlog "build-fork[$key]: resign failed"; exit 1; }
  fi
  _enforce_signature "$DIST_DIR/cloud-nixdroid.apk"
  _assert_apk_identity "$key" "$DIST_DIR/cloud-nixdroid.apk"
  log "→ $DIST_DIR/cloud-nixdroid.apk ($(wc -c <"$DIST_DIR/cloud-nixdroid.apk") B)"
}

# ── publish-fork <key> ─────────────────────────────────────────────────
# Push a built fork APK to GHCR as an OCI artifact (image from
# build.json::forks.<key>.image; registry/namespace shared with release.ghcr).
# Tags: latest + sha-{sha}. The hub's bundle-forks + FleetUpdater consume this.
step_publish_fork() {
  local key="${2:-}"
  [ -n "$key" ] || { errlog "usage: build.sh publish-fork <mail|chat|matrix>"; exit 1; }
  local image registry namespace media_type artifact sha
  image="$(_fork_json "$key" ".image")"
  registry="$(_json '.release.ghcr.registry')"
  namespace="$(_json '.release.ghcr.namespace')"
  media_type="$(_json '.release.ghcr.media_type')"
  artifact="$DIST_DIR/cloud-nixdroid.apk"
  [ -f "$artifact" ] || { errlog "publish-fork[$key]: $artifact missing — run build-fork first"; exit 1; }
  sha="${GITHUB_SHA:-$(prefer_host git -C "$SCRIPT_DIR" rev-parse --short=8 HEAD 2>/dev/null || echo unknown)}"
  # ABI-aware tag suffix — mirrors build-fork's COMMS_BUNDLE_ABI. The default
  # arm64-v8a publishes the bare tags (latest, sha-x); a non-default abi (e.g.
  # x86_64, resigned from .forks.<key>.upstream_apk.abi_variants.x86_64)
  # publishes latest-<abi> — the exact tag the in-app fleet updater tries first
  # on that device before falling back to the universal `latest`.
  local abi="${COMMS_BUNDLE_ABI:-arm64-v8a}" suffix=""
  [ "$abi" = "arm64-v8a" ] || suffix="-$abi"
  local tag ref
  for tag in latest "sha-${sha:0:8}"; do
    ref="$registry/$namespace/$image:${tag}${suffix}"
    log "publish-fork[$key]: oras push $ref"
    ( cd "$DIST_DIR" && in_nix oras push "$ref" "cloud-nixdroid.apk:$media_type" \
        --artifact-type "$media_type" )
  done
}

# ── GHCR distribution (hub APK) ─────────────────────────────────────────
# Same OCI HTTP flow as aa_cloud-superapp; the hub's in-app updater drives the
# on-device fleet. Registry/tags are data-driven from build.json::release.ghcr.

# Pull an APK blob from GHCR by <image>:<tag> into <outfile>. Returns non-zero on
# 404 / any error so callers (bundle-forks) can skip gracefully. Shared OCI HTTP
# flow; registry/namespace from build.json::release.ghcr.
_oci_pull_blob() {
  local image="$1" tag="$2" out="$3"
  local registry namespace repo token manifest digest
  registry="$(_json '.release.ghcr.registry')"
  namespace="$(_json '.release.ghcr.namespace')"
  repo="$namespace/$image"
  token="$(curl -sf "https://$registry/token?service=$registry&scope=repository:$repo:pull" 2>/dev/null | jq -r .token 2>/dev/null)" || return 1
  [ -n "$token" ] && [ "$token" != "null" ] || return 1
  manifest="$(curl -sfL -H "Authorization: Bearer $token" \
    -H "Accept: application/vnd.oci.image.manifest.v1+json" \
    "https://$registry/v2/$repo/manifests/$tag" 2>/dev/null)" || return 1
  digest="$(jq -r '.layers[0].digest' <<<"$manifest" 2>/dev/null)"
  [ -n "$digest" ] && [ "$digest" != "null" ] || return 1
  curl -sfL -H "Authorization: Bearer $token" \
    "https://$registry/v2/$repo/blobs/$digest" -o "$out" 2>/dev/null || return 1
  [ -s "$out" ] || return 1
  return 0
}

# Fetch a pinned upstream release APK: curl + sha256 verify (+ optional
# constellation re-sign for upstreams that publish unsigned APKs). Tools:
# zipalign/apksigner from $ANDROID_HOME/build-tools (present in the hub CI via
# setup-android; locally requires the devShell). Returns non-zero on any
# mismatch/missing-tool so the caller skips gracefully — never embeds an
# unverified or unsigned APK.
_fetch_upstream_apk() {
  local key="$1" url="$2" sha="$3" resign="$4" out="$5"
  local tmp="${out}.dl"
  curl -sfL --retry 3 -o "$tmp" "$url" || { errlog "upstream[$key]: download failed"; rm -f "$tmp"; return 1; }
  local got; got="$(sha256sum "$tmp" | cut -d' ' -f1)"
  if [ "$got" != "$sha" ]; then
    errlog "upstream[$key]: sha256 mismatch (got $got, pinned $sha)"; rm -f "$tmp"; return 1
  fi
  if [ "$resign" = "true" ]; then
    # Resign with the ONE shared constellation key (fails loud if unavailable).
    _resign_apk "$tmp" "$out" || { rm -f "$tmp"; return 1; }
    rm -f "$tmp"
  else
    mv "$tmp" "$out"
  fi
  return 0
}

# Resign an APK (in-place-safe: in != out) with the ONE shared constellation
# key — zipalign + apksigner. Used by _fetch_upstream_apk (downloaded unsigned
# stock APKs) AND by from-source forks whose upstream build emits an UNSIGNED
# APK (build.json::forks.<key>.build.resign_unsigned). Tools: zipalign/apksigner
# from $ANDROID_HOME/build-tools (hub CI via setup-android). Fails loud.
_resign_apk() {
  local in="$1" out="$2" bt zipalign apksigner ks
  _resolve_signing
  bt="$(ls -d "${ANDROID_HOME:-/nonexistent}"/build-tools/* 2>/dev/null | sort -V | tail -1)"
  zipalign="$bt/zipalign"; apksigner="$bt/apksigner"; ks="$ANDROID_KEYSTORE_FILE"
  if [ ! -x "$zipalign" ] || [ ! -x "$apksigner" ] || [ ! -f "$ks" ]; then
    errlog "resign: zipalign/apksigner/keystore missing (bt=$bt ks=$ks)"; return 1
  fi
  "$zipalign" -f 4 "$in" "${in}.aligned" || { rm -f "${in}.aligned"; return 1; }
  "$apksigner" sign --ks "$ks" --ks-pass "pass:$ANDROID_KEYSTORE_PASSWORD" \
    --ks-key-alias "$ANDROID_KEY_ALIAS" --key-pass "pass:${ANDROID_KEY_PASSWORD:-$ANDROID_KEYSTORE_PASSWORD}" \
    --out "$out" "${in}.aligned" || { rm -f "${in}.aligned"; return 1; }
  rm -f "${in}.aligned" "${out}.idsig"
  return 0
}

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

  # CREATE WITH GITHUB_TOKEN, UPDATE WITH THE PAT — each token for the one thing
  # it can do. A repo-scoped GITHUB_TOKEN creates a package carrying the repo's
  # visibility; the user-scoped PAT creates it PRIVATE, which in a public repo
  # 401s every unauthenticated pull. But GITHUB_TOKEN cannot UPDATE a package in
  # the user namespace (af6767fdb), so the token is chosen per PACKAGE, not per
  # repo. Measured 2026-08-30: cloud-lib-search and cloud-lib-watchdog were
  # deleted and recreated through this path and came back PUBLIC, after coming
  # back private every single time the PAT created them.
  local creds=()
  if [ -n "${GHCR_CREATE_TOKEN:-}" ] && command -v gh >/dev/null 2>&1 \
     && ! gh api "/user/packages/container/${image}" >/dev/null 2>&1; then
    log "ghcr: ${image} does not exist — creating it with GITHUB_TOKEN so it inherits the repo"
    creds=(--username "${GITHUB_ACTOR:-diegonmarcos}" --password "${GHCR_CREATE_TOKEN}")
  fi
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
    ( cd "$adir" && in_nix oras push "${creds[@]}" "$ref" "$aname:$media_type" --artifact-type "$media_type" )
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
  asset_title="$(jq -r '.layers[0].annotations["org.opencontainers.image.title"] // "cloud-comms-hub.apk"' <<<"$manifest")"
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
# Same rolling-release behaviour as aa_cloud-superapp: publish the hub APK to a
# single release (release.gh_release.rolling_tag, default `latest`), overwriting
# the asset every main push so /releases/latest/download/<asset> is a stable
# URL. Also creates an immutable per-tag release when invoked under a tag push.
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

# ── GitHub Release for a FORK APK ───────────────────────────────────────
# AUTOMATIC for every fork — no per-fork config. Uploads the built
# dist/cloud-nixdroid.apk under its own name to the SAME rolling release
# the hub uses (release.gh_release.rolling_tag) with --clobber, so the
# release page mirrors GHCR for the whole constellation.
step_gh_release_fork() {
  local key="${2:-}"
  [ -n "$key" ] || { errlog "usage: build.sh gh-release-fork <mail|chat|matrix|dialer>"; exit 1; }
  local rolling_tag src
  rolling_tag="$(_json '.release.gh_release.rolling_tag')"
  [ -n "$rolling_tag" ] && [ "$rolling_tag" != "null" ] || { errlog "gh-release-fork[$key]: release.gh_release.rolling_tag unset"; exit 1; }
  src="$DIST_DIR/cloud-nixdroid.apk"
  [ -f "$src" ] || { errlog "gh-release-fork[$key]: $src missing — run build-fork first"; exit 1; }
  if ! in_nix gh release view "$rolling_tag" >/dev/null 2>&1; then
    in_nix gh release create "$rolling_tag" --title "$rolling_tag" \
      --target "${GITHUB_SHA:-main}" \
      --notes "Rolling release — overwritten on every main push." --latest
  fi
  log "gh-release-fork[$key]: upload cloud-nixdroid.apk → $rolling_tag"
  in_nix gh release upload "$rolling_tag" "$src" --clobber
}

# Main-guard: allow this file to be `source`d (e.g. by tests) to exercise the
# resolution helpers (_is_hubless / _single_fork_key / _launcher_activity /
# _json) without running any real gradle/adb/network command.
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
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
    bundle-forks)     step_bundle_forks ;;
    materialize-fork) step_materialize_fork "$@" ;;
    build-fork)       step_build_fork "$@" ;;
    publish-fork)     step_publish_fork "$@" ;;
    oras-push)        step_oras_push ;;
    oras-pull)        step_oras_pull "$@" ;;
    phone-install)    step_phone_install "$@" ;;
    gh-release)       step_gh_release ;;
    gh-release-fork)  step_gh_release_fork "$@" ;;
    help|*)
      sed -n '2,/^set -euo/p' "$0" | sed 's/^# *//; /^set/d; /^$/d'
      ;;
  esac
fi
