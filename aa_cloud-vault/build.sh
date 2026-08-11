#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Cloud Vault — Universal Build Dispatcher                              ║
# ║                                                                  ║
# ║ Modularized monolith Android app (maps/navigation/tracker).      ║
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
# ║   materialize-fork <key>  clone upstream@pin → tracker + patches  ║
# ║   build-fork <key>        fork's own gradlew + constellation sign ║
# ║                                                                  ║
# ║ build/release above are UNCHANGED — they still build the in-tree  ║
# ║ WebView wrapper. materialize-fork/build-fork are a SEPARATE       ║
# ║ opt-in path (build.json::forks.vault) for the Bitwarden-fork      ║
# ║ rebuild — same fork machinery ea_cloud-mail uses (clone pinned    ║
# ║ tag into gitignored tracker + apply patches/, ported verbatim).   ║
# ║                                                                  ║
# ║ NEVER bypass this script for build operations.                    ║
# ╚══════════════════════════════════════════════════════════════════╝
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="$SCRIPT_DIR/dist"
CMD="${1:-help}"
APP_MAIN="com.diegonmarcos.cloudvault.MainActivity"

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

# ── fork-scoped JSON read (build.json::forks.<key>) ─────────────────────
# ALWAYS use this rather than interpolating the fork key into a jq path via
# _release_var — a fork key containing a hyphen is parsed by jq as
# subtraction when interpolated into a path. Passing the key as DATA
# (--arg) and indexing with brackets makes any key safe and closes a
# shell->jq injection path. Ported verbatim from ea_cloud-mail's engine
# (cloud-mail-fork-engine.sh::_fork_json) — same semantics, same signature.
#   _fork_json "$key" '.build.gradle_task'
#   _fork_json "$key" ''                    # the whole fork object
_fork_json() {
  local k="$1" sub="${2:-}"
  prefer_host jq -r --arg k "$k" ".forks[\$k]${sub} // empty" "$SCRIPT_DIR/build.json"
}

# Guard against shipping an APK with the wrong package identity. Ported
# verbatim from ea_cloud-mail's engine.
# $1=key, $2=apk path, $3=expected package id (defaults to .forks.<key>.app_id).
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
    errlog "  Package-shaped strings found: $(printf '%s\n' "$pkgs" \
      | grep -E '^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$' | sort -u | head -5 | tr '\n' ' ')"
    exit 1
  fi
  log "identity-assert[$key]: OK — package $expected_id confirmed in manifest"
}

# Resign an APK (in-place-safe: in != out) with the ONE shared constellation
# key — zipalign + apksigner. Ported verbatim from ea_cloud-mail's engine.
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

# Fetch a pinned upstream release APK: curl + sha256 verify (+ optional
# constellation re-sign). Ported verbatim from ea_cloud-mail's engine. Not
# exercised by the vault fork today (it builds from source), kept for parity
# so a future upstream-APK fork under this build.sh works unmodified.
_fetch_upstream_apk() {
  local key="$1" url="$2" sha="$3" resign="$4" out="$5"
  local tmp="${out}.dl"
  curl -sfL --retry 3 -o "$tmp" "$url" || { errlog "upstream[$key]: download failed"; rm -f "$tmp"; return 1; }
  local got; got="$(sha256sum "$tmp" | cut -d' ' -f1)"
  if [ "$got" != "$sha" ]; then
    errlog "upstream[$key]: sha256 mismatch (got $got, pinned $sha)"; rm -f "$tmp"; return 1
  fi
  if [ "$resign" = "true" ]; then
    _resign_apk "$tmp" "$out" || { rm -f "$tmp"; return 1; }
    rm -f "$tmp"
  else
    mv "$tmp" "$out"
  fi
  return 0
}

# ── materialize-fork <key> ─────────────────────────────────────────────
# Declaratively reconstruct a fork: clone the upstream at the pinned tag into
# its (gitignored) tracker dir, then apply the committed patch series. Same
# input → same working tree. NEVER produces a long-lived divergent clone.
# Ported verbatim (same semantics) from ea_cloud-mail's
# cloud-mail-fork-engine.sh::step_materialize_fork.
step_materialize_fork() {
  local key="${2:-}"
  [ -n "$key" ] || { errlog "usage: build.sh materialize-fork <vault>"; exit 1; }

  local repo tracker tag blocked mtask
  repo="$(_fork_json "$key" ".upstream_repo")"
  tracker="$(_fork_json "$key" ".tracker_dir")"
  tag="$(_fork_json "$key" ".pinned_tag")"
  blocked="$(_fork_json "$key" ".blocked_on")"
  [ -n "$repo" ] && [ -n "$tracker" ] || { errlog "unknown fork '$key' in build.json::forks"; exit 1; }

  # Upstream-APK forks (no gradle_task AND no build.command) build from the
  # pinned release APK, not source — nothing to clone/patch.
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
  # Per-app model: patches always live beside the build.sh entrypoint
  # (ea_cloud-vault/patches/). SCRIPT_DIR resolves to the invocation dir.
  local patch_dir="$SCRIPT_DIR/patches"

  if [ ! -d "$dest/.git" ]; then
    log "materialize-fork[$key]: cloning $repo → $tracker (tag $tag)"
    # tracker_dir may be nested (ea_upstreams-sources/<name>); the parent is
    # gitignored workspace and won't exist on a fresh CI checkout.
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
      # Explicit ident: CI runners have no git identity; the applied commits
      # are reproducible-engine output, not authored work.
      prefer_host git -C "$dest" \
        -c user.name="cloud-comms-engine" \
        -c user.email="engine@diegonmarcos.com" \
        am "$p"
    done
  fi
  log "materialize-fork[$key]: ✓ $tracker ready (build with: ./build.sh build-fork $key)"
}

# ── build-fork <key> ───────────────────────────────────────────────────
# Build a materialized fork's APK with the fork's OWN gradle wrapper (each
# upstream pins its own Gradle/AGP — never our devShell gradle). Everything
# is data-driven from build.json::forks.<key>.build. Ported verbatim (same
# semantics) from ea_cloud-mail's cloud-mail-fork-engine.sh::step_build_fork.
step_build_fork() {
  local key="${2:-}"
  [ -n "$key" ] || { errlog "usage: build.sh build-fork <vault>"; exit 1; }
  local tracker dest task apk_glob signing
  tracker="$(_fork_json "$key" ".tracker_dir")"
  task="$(_fork_json "$key" ".build.gradle_task_by_abi[\"${COMMS_BUNDLE_ABI:-arm64-v8a}\"]")"
  [ -n "$task" ] || task="$(_fork_json "$key" ".build.gradle_task")"
  apk_glob="$(_fork_json "$key" ".build.apk_glob")"
  signing="$(_fork_json "$key" ".build.signing")"
  dest="$SCRIPT_DIR/../$tracker"

  # ── Upstream-APK fork (no gradle_task AND no build.command): ships the
  #    pinned upstream release APK re-signed with the ONE shared
  #    constellation key. Needs no materialized source.
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
    _fetch_upstream_apk "$key" "$up_url" "$up_sha" "$up_resign" "$DIST_DIR/cloud-vault-${key}.apk" \
      || { errlog "build-fork[$key]: upstream APK fetch/resign failed"; exit 1; }
    _enforce_signature "$DIST_DIR/cloud-vault-${key}.apk"
    local up_pkg; up_pkg="$(_fork_json "$key" ".upstream_apk.package")"
    if [ -z "$up_pkg" ] || [ "$up_pkg" = "null" ]; then up_pkg=""; fi
    _assert_apk_identity "$key" "$DIST_DIR/cloud-vault-${key}.apk" "$up_pkg"
    log "build-fork[$key]: upstream-APK fork ($bundle_abi) → $DIST_DIR/cloud-vault-${key}.apk ($(wc -c <"$DIST_DIR/cloud-vault-${key}.apk") B)"
    return 0
  fi

  [ -d "$dest/.git" ] || { errlog "fork '$key' not materialized — run: ./build.sh materialize-fork $key"; exit 1; }

  if [ "$signing" = "keystore_properties" ]; then
    _resolve_signing
    printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
      "$ANDROID_KEYSTORE_FILE" "$ANDROID_KEYSTORE_PASSWORD" \
      "$ANDROID_KEY_ALIAS" "${ANDROID_KEY_PASSWORD:-$ANDROID_KEYSTORE_PASSWORD}" \
      > "$dest/keystore.properties"
    log "build-fork[$key]: keystore.properties → ONE shared constellation key"
  fi

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

  # Data-driven PRE-BUILD steps (build.json::forks.<key>.build.prepare[]).
  while IFS= read -r pcmd; do
    [ -n "$pcmd" ] || continue
    log "build-fork[$key]: prepare → $pcmd"
    ( cd "$dest" && in_nix bash -lc "$pcmd" ) || { errlog "build-fork[$key]: prepare failed: $pcmd"; exit 1; }
  done < <(prefer_host jq -r --arg k "$key" '.forks[$k].build.prepare // [] | .[]' "$SCRIPT_DIR/build.json")

  # Data-driven gradle -P properties from build.json::forks.<key>.build.gradle_props.
  local -a gprops=()
  while IFS=$'\t' read -r gp_key gp_val; do
    [ -n "$gp_key" ] || continue
    if [[ "$gp_val" == '$ENV:'* ]]; then
      local env_var="${gp_val#'$ENV:'}"
      gp_val="${!env_var:-dev}"
    fi
    gprops+=("-P${gp_key}=${gp_val}")
  done < <(prefer_host jq -r --arg k "$key" '.forks[$k].build.gradle_props // {} | to_entries[] | select(.key | startswith("_") | not) | "\(.key)\t\(.value)"' "$SCRIPT_DIR/build.json")

  # Build command is data-driven: build.command overrides the default
  # gradlew invocation.
  local bcmd; bcmd="$(_fork_json "$key" ".build.command")"
  if [ -n "$bcmd" ] && [ "$bcmd" != "null" ]; then
    log "build-fork[$key]: $tracker → $bcmd (upstream build wrapper)"
    ( cd "$dest" && in_nix bash -lc "$bcmd" )
  else
    log "build-fork[$key]: $tracker ./gradlew $task ${gprops[*]:-(no -P props)} (upstream-pinned toolchain)"
    ( cd "$dest" && chmod +x gradlew && in_nix ./gradlew --no-daemon "$task" "${gprops[@]}" )
  fi

  mkdir -p "$DIST_DIR"
  shopt -s nullglob globstar
  local apks=("$dest"/$apk_glob)
  shopt -u nullglob globstar
  [ "${#apks[@]}" -ge 1 ] || { errlog "build-fork[$key]: no APK matched $apk_glob"; exit 1; }
  cp "${apks[0]}" "$DIST_DIR/cloud-vault-${key}.apk"
  if [ "$(_fork_json "$key" ".build.resign_unsigned")" = "true" ]; then
    log "build-fork[$key]: resign unsigned build with constellation key"
    _resign_apk "$DIST_DIR/cloud-vault-${key}.apk" "$DIST_DIR/cloud-vault-${key}.apk.signed" \
      && mv "$DIST_DIR/cloud-vault-${key}.apk.signed" "$DIST_DIR/cloud-vault-${key}.apk" \
      || { errlog "build-fork[$key]: resign failed"; exit 1; }
  fi
  _enforce_signature "$DIST_DIR/cloud-vault-${key}.apk"
  _assert_apk_identity "$key" "$DIST_DIR/cloud-vault-${key}.apk"
  log "→ $DIST_DIR/cloud-vault-${key}.apk ($(wc -c <"$DIST_DIR/cloud-vault-${key}.apk") B)"
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
# CLOUDVAULT_VARIANT (env) selects a release.variants[] entry. Unset = arm64
# default → every helper falls back to the legacy single-variant keys.
_variant_field() {
  local v="${CLOUDVAULT_VARIANT:-}"
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

# Export CLOUDVAULT_ABIS (CSV) for gradle from the active variant. No-op when
# unset → gradle reads build.json::android.abi_filters.
_export_variant_abis() {
  local csv; csv="$(_variant_field '.abis | join(",")')"
  if [ -n "$csv" ]; then
    export CLOUDVAULT_ABIS="$csv"
    log "Variant ${CLOUDVAULT_VARIANT:-}: ABIs=$csv"
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
  asset_title="$(jq -r '.layers[0].annotations["org.opencontainers.image.title"] // "cloud-vault.apk"' <<<"$manifest")"
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
  materialize-fork) step_materialize_fork "$@" ;;
  build-fork)       step_build_fork "$@" ;;
  help|*)
    sed -n '2,/^set -euo/p' "$0" | sed 's/^# *//; /^set/d; /^$/d'
    ;;
esac
