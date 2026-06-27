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

# ── bundle-forks ───────────────────────────────────────────────────────
# SELF-CONTAINED wrapper bundle: for every fork in build.json::forks that has
# an `embedded` block, download the REAL upstream release APK (pinned URL),
# verify its pinned sha256 (HARD FAIL on mismatch — supply-chain gate), and
# place it at hub/src/main/assets/forks/<key>.apk so the single Cloud-IDE APK
# physically carries Acode + Amaze inside it. Cached: a file already matching
# the pin is not re-downloaded. Mirrors ea_cloud-comms' bundle-forks, with the
# source swapped from GHCR to the pinned upstream release (until our patched
# forks publish). Called automatically by build/release.
# Resolve an ABI (explicit arg, or build.json::release.abis default) into
# "abi|rust_target|artifact_suffix". Single source of truth = build.json.
_abi_fields() {
  local abi="${1:-}"
  [ -n "$abi" ] || abi="$(prefer_host jq -r '[.release.abis | to_entries[] | select((.key|startswith("_")|not) and (.value|type=="object") and (.value.default==true)) | .key][0] // "arm64-v8a"' "$SCRIPT_DIR/build.json")"
  local rt def
  rt="$(_json ".release.abis[\"$abi\"].rust_target")"
  def="$(_json ".release.abis[\"$abi\"].default")"
  [ -n "$rt" ] || { errlog "unknown abi '$abi' — not in build.json::release.abis"; return 1; }
  local suffix=""; [ "$def" = "true" ] || suffix="-$abi"
  echo "${abi}|${rt}|${suffix}"
}

step_bundle_forks() {
  local abi_in="${1:-}"
  local assets="$SCRIPT_DIR/hub/src/main/assets/forks"
  mkdir -p "$assets"
  local key src url sha out have
  while IFS= read -r key; do
    [ -z "$key" ] && continue
    # Source mode (data-driven): "build" = OUR patched fork APK (build-fork);
    # "url" = pinned upstream release (fallback). Default = url when a pinned
    # url+sha exist. A fork with neither is simply absent from the bundle.
    src="$(_json ".forks.${key}.embedded.source")"
    url="$(_json ".forks.${key}.embedded.url")"
    sha="$(_json ".forks.${key}.embedded.sha256")"
    out="$assets/${key}.apk"
    [ -n "$src" ] || { [ -n "$url" ] && [ -n "$sha" ] && src="url" || continue; }

    if [ "$src" = "build" ]; then
      # Build our patched fork → embed dist/cloud-ide-<key>.apk. If the build
      # toolchain isn't available here (e.g. CI without rust/ndk) fall back to
      # the pinned upstream url so the hub still builds — LOUDLY logged, never
      # silent. (CI ships our patched APK via the GHCR route once fork-ship lands.)
      if ( step_build_fork build-fork "$key" "$abi_in" ) >/dev/null 2>&1; then  # subshell: contain any exit→ url fallback works (CI: fork not materialized / no rust)
        cp "$SCRIPT_DIR/dist/cloud-ide-${key}.apk" "$out"
        log "bundle-forks: ✓ $key embedded OUR patched APK ($(wc -c <"$out") B, $(sha256sum "$out" | cut -d' ' -f1 | cut -c1-12)…)"
        continue
      elif [ -n "$url" ] && [ -n "$sha" ]; then
        errlog "bundle-forks: $key build-fork failed/unavailable — FALLING BACK to pinned upstream url"
        src="url"
      else
        errlog "bundle-forks: $key source=build failed and no url fallback — refusing"; exit 1
      fi
    fi

    # url source (or fallback): pinned upstream release, sha256-verified.
    if [ -f "$out" ] && [ "$(sha256sum "$out" | cut -d' ' -f1)" = "$sha" ]; then
      log "bundle-forks: $key already bundled + verified — skip"; continue
    fi
    rm -f "$out"
    log "bundle-forks: fetching $key ← $url"
    curl -fsSL --retry 3 -o "$out" "$url"
    have="$(sha256sum "$out" | cut -d' ' -f1)"
    if [ "$have" != "$sha" ]; then
      rm -f "$out"
      errlog "bundle-forks: sha256 MISMATCH for $key (got $have, pinned $sha) — refusing to bundle"; exit 1
    fi
    log "bundle-forks: ✓ $key embedded upstream ($(wc -c <"$out") B, sha verified)"
  done < <(prefer_host jq -r '.forks | to_entries[] | select(.key|startswith("_")|not) | .key' "$SCRIPT_DIR/build.json")
  shopt -s nullglob
  local apks=("$assets"/*.apk)
  shopt -u nullglob
  log "bundle-forks: ${#apks[@]} app(s) self-contained in the hub bundle"
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
  if [ -n "${ANDROID_KEYSTORE_FILE:-}" ] && [ -f "${ANDROID_KEYSTORE_FILE}" ] && [ -n "${ANDROID_KEY_ALIAS:-}" ]; then
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
  store_pw="$(sops -d --extract '["keystore_password"]' "$vault/$sec_rel" 2>/dev/null || true)"
  key_pw="$(sops -d --extract '["key_password"]' "$vault/$sec_rel" 2>/dev/null || true)"
  alias_="$(sops -d --extract '["key_alias"]' "$vault/$sec_rel" 2>/dev/null || true)"
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

# ── hub build/test ─────────────────────────────────────────────────────
step_build() {
  local abifields abi suffix
  abifields="$(_abi_fields "${2:-}")" || exit 1
  abi="${abifields%%|*}"; suffix="$(echo "$abifields" | cut -d'|' -f3)"
  step_bundle_forks "$abi"
  _resolve_signing
  log "Build: cloud-ide wrapper (hub + embedded Acode/Amaze, debug APK, abi $abi)"
  in_nix gradle :hub:assembleDebug
  mkdir -p "$DIST_DIR"
  local base out; base="$(_json '.release.artifact.debug')"
  out="$DIST_DIR/${base%.apk}${suffix}.apk"
  cp "$SCRIPT_DIR/hub/build/outputs/apk/debug/hub-debug.apk" "$out"
  log "→ $out"
}

step_release() {
  local abifields abi suffix
  abifields="$(_abi_fields "${2:-}")" || exit 1
  abi="${abifields%%|*}"; suffix="$(echo "$abifields" | cut -d'|' -f3)"
  step_bundle_forks "$abi"
  _resolve_signing
  log "Build: cloud-ide wrapper (hub + embedded Acode/Amaze, release APK, abi $abi)"
  in_nix gradle :hub:assembleRelease
  mkdir -p "$DIST_DIR"
  local base out; base="$(_json '.release.artifact.release')"
  out="$DIST_DIR/${base%.apk}${suffix}.apk"
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
  local c_auth c_perm c_ver c_svc c_ofa b_auth b_perm b_ver b_svc b_ofa
  c_auth="$(prefer_host jq -r '.authority' "$contract")"
  c_perm="$(prefer_host jq -r '.permission' "$contract")"
  c_ver="$(prefer_host jq -r '.version' "$contract")"
  c_svc="$(prefer_host jq -r '.aidl.service_interface' "$contract")"
  c_ofa="$(prefer_host jq -r '.navigation.open_fork_action' "$contract")"
  b_auth="$(_json '.ipc.authority')"; b_perm="$(_json '.ipc.permission')"
  b_ver="$(_json '.ipc.version')";    b_svc="$(_json '.ipc.aidl_service')"
  b_ofa="$(_json '.ipc.open_fork_action')"
  local ok=1
  [ "$c_auth" = "$b_auth" ] || { errlog "authority mismatch: contract=$c_auth build.json=$b_auth"; ok=0; }
  [ "$c_perm" = "$b_perm" ] || { errlog "permission mismatch: contract=$c_perm build.json=$b_perm"; ok=0; }
  [ "$c_ver"  = "$b_ver"  ] || { errlog "version mismatch: contract=$c_ver build.json=$b_ver"; ok=0; }
  [ "$c_svc"  = "$b_svc"  ] || { errlog "aidl_service mismatch: contract=$c_svc build.json=$b_svc"; ok=0; }
  [ "$c_ofa"  = "$b_ofa"  ] || { errlog "open_fork_action mismatch: contract=$c_ofa build.json=$b_ofa"; ok=0; }
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
# Build a materialized fork's APK with the fork's OWN build system — its
# checked-in gradle wrapper (pinned gradle version) and the task declared in
# build.json::forks.<key>.build.task (flavors differ per fork). The devShell
# supplies JDK + ANDROID_HOME; the wrapper supplies gradle. Output resolved
# from build.json::forks.<key>.build.apk_glob → dist/cloud-ide-<key>.apk.
step_build_fork() {
  local key="${2:-}"
  [ -n "$key" ] || { errlog "usage: build.sh build-fork <files|utils|editor> [abi]"; exit 1; }
  # ABI → rust cargo target (data-driven). Passed to the fork's gradle as
  # -PcloudIdeRustTargets so the patched native module builds for this ABI only.
  local abifields abi rust_target gprops=()
  abifields="$(_abi_fields "${3:-}")" || exit 1
  abi="${abifields%%|*}"; rust_target="$(echo "$abifields" | cut -d'|' -f2)"
  [ -n "$rust_target" ] && gprops+=("-PcloudIdeRustTargets=$rust_target")
  local tracker dest task glob
  tracker="$(_json ".forks.${key}.tracker_dir")"
  task="$(_json ".forks.${key}.build.task")"; task="${task:-assembleRelease}"
  glob="$(_json ".forks.${key}.build.apk_glob")"
  dest="$SCRIPT_DIR/../$tracker"
  [ -d "$dest/.git" ] || { errlog "fork '$key' not materialized — run: ./build.sh materialize-fork $key"; exit 1; }

  # Provision the fork's signing keystore BEFORE gradle config (the patch's
  # signing config references it at evaluation time). The fork APKs MUST sign
  # with the ONE shared constellation key like every other app — so instead of
  # generating a random per-fork key, we IMPORT the resolved vault keypair into
  # the keystore file the fork's gradle expects (storepass/keypass 'android',
  # alias from build.json). The APK signature is the keypair, not the alias or
  # store password, so every fork APK signs with the SAME constellation cert.
  # NO random keytool -genkeypair, EVER. _resolve_signing fails loud (exit 1)
  # if the shared key is unavailable — no random/legacy fallback.
  local ks alias kspath
  ks="$(_json ".forks.${key}.build.keystore")"
  alias="$(_json ".forks.${key}.build.keystore_alias")"; alias="${alias:-idekey}"
  if [ -n "$ks" ]; then
    kspath="$dest/$ks"
    _resolve_signing
    log "build-fork[$key]: importing ONE shared constellation key → $ks (alias $alias)"
    rm -f "$kspath"
    mkdir -p "$(dirname "$kspath")"
    in_nix keytool -importkeystore -noprompt \
      -srckeystore "$ANDROID_KEYSTORE_FILE" -srcstoretype PKCS12 \
      -srcstorepass "$ANDROID_KEYSTORE_PASSWORD" -srcalias "$ANDROID_KEY_ALIAS" \
      -srckeypass "${ANDROID_KEY_PASSWORD:-$ANDROID_KEYSTORE_PASSWORD}" \
      -destkeystore "$kspath" -deststoretype PKCS12 \
      -deststorepass android -destkeypass android -destalias "$alias"
  fi

  # ABI-switch clean: rust-android-gradle leaves prior-ABI .so in
  # build/rustJniLibs and AGP's mergeNativeLibs is cached, so switching ABI
  # without a clean packages a STALE ABI. Track the last-built target in a
  # marker (gitignored tracker file); clean when it changes → each per-ABI APK
  # contains exactly its own ABI.
  local marker="$dest/.cloud-ide-built-target"
  if [ ! -f "$marker" ] || [ "$(cat "$marker" 2>/dev/null)" != "$rust_target" ]; then
    log "build-fork[$key]: ABI is $rust_target (was $(cat "$marker" 2>/dev/null || echo none)) — gradle clean for a pure single-ABI APK"
    if [ -x "$dest/gradlew" ]; then ( cd "$dest" && in_nix ./gradlew --no-daemon clean ); else ( cd "$dest" && in_nix gradle clean ); fi
    rm -f "$marker"
  fi

  log "build-fork[$key]: $tracker → $task (abi $abi, rust $rust_target)"
  if [ -x "$dest/gradlew" ]; then
    ( cd "$dest" && in_nix ./gradlew --no-daemon "$task" "${gprops[@]}" )
  else
    ( cd "$dest" && in_nix gradle "$task" "${gprops[@]}" )
  fi
  echo "$rust_target" > "$marker"
  mkdir -p "$DIST_DIR"
  local apk=""
  if [ -n "$glob" ]; then
    shopt -s nullglob; local hits=("$dest"/$glob); shopt -u nullglob
    [ "${#hits[@]}" -ge 1 ] && apk="${hits[0]}"
  fi
  [ -n "$apk" ] || apk="$(prefer_host find "$dest" -path '*/outputs/apk/*release*.apk' -print -quit 2>/dev/null || true)"
  [ -n "$apk" ] || { errlog "build-fork[$key]: no APK produced (glob: ${glob:-<default>})"; exit 1; }
  cp "$apk" "$DIST_DIR/cloud-ide-${key}.apk"
  log "→ $DIST_DIR/cloud-ide-${key}.apk ($(wc -c <"$DIST_DIR/cloud-ide-${key}.apk") B)"
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
    suffix="$(_abi_fields "$abi" | cut -d'|' -f3)"
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
  verify-contract)  step_verify_contract ;;
  bundle-forks)     step_bundle_forks "${2:-}" ;;
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
