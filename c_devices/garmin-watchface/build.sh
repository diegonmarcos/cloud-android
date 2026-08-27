#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Garmin Watch Face — Universal, design-aware build engine          ║
# ║                                                                  ║
# ║ Multi-design CIQ monorepo. Each design = faces/<id>/ (own UUID,   ║
# ║ manifest, source, resources) + its OWN geometry in design.json.   ║
# ║ The Connect IQ SDK (monkeyc) comes from flake.nix — the Nix way,  ║
# ║ never an imperative install. Publishing mirrors aa_cloud-superapp.║
# ║                                                                  ║
# ║ Commands ( [design] defaults to build.json::build.default_design ):║
# ║   gen        [design]  design.json → DesignConfig.mc + launcher   ║
# ║   sdk        [design]  provision Connect IQ SDK (Garmin login)    ║
# ║   build      [design]  gen + monkeyc → dist/<design>/<design>.prg  ║
# ║   package    [design]  gen + monkeyc → dist/<design>/<design>.iq   ║
# ║                        (store-uploadable, all products)           ║
# ║   sim        [design]  build + boot the connectiq simulator       ║
# ║   secrets              sops-decrypt vault dev key → dist/          ║
# ║   oras-push  [design]  push .prg → GHCR as OCI artifact           ║
# ║   oras-pull  [design]  pull .prg from GHCR → dist/                 ║
# ║   gh-release [design]  attach .prg to rolling GitHub release      ║
# ║   clean      [design]  rm generated + dist/ (all designs if none) ║
# ║   shell                enter the Nix devShell                     ║
# ║                                                                  ║
# ║ NEVER bypass this script for build operations.                    ║
# ╚══════════════════════════════════════════════════════════════════╝
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FACES_DIR="$SCRIPT_DIR/faces"
DIST_DIR="$SCRIPT_DIR/dist"
LIB_DIR="$SCRIPT_DIR/lib"
BUILD_JSON="$SCRIPT_DIR/build.json"

log()    { printf "[%s] %s\n" "$(date '+%H:%M:%S')" "$1"; }
errlog() { printf "\033[0;31m[%s] ERROR: %s\033[0m\n" "$(date '+%H:%M:%S')" "$1" >&2; }
die()    { errlog "$1"; exit 1; }

# ── Tool resolution ────────────────────────────────────────────────
# Every SDK call (monkeyc/connectiq) goes through `nix develop` so the
# SDK + JDK are reproducible per flake.nix. Set BYPASS_NIX=1 when the
# environment already provides them (CI provisions the SDK itself).
in_nix() {
  if [ "${BYPASS_NIX:-0}" = "1" ]; then
    "$@"
  else
    command -v nix >/dev/null 2>&1 || die "nix not on PATH; install nix or set BYPASS_NIX=1"
    nix develop "$SCRIPT_DIR" --command "$@"
  fi
}

# jq is mandatory for reading build.json / design.json.
jqr() { command -v jq >/dev/null 2>&1 || die "jq not found (provided by the devShell)"; jq "$@"; }

bj()  { jqr -r "$1" "$BUILD_JSON"; }                       # read build.json
dj()  { jqr -r "$2" "$FACES_DIR/$1/design.json"; }         # read a design's design.json

# ── Connect IQ SDK provisioning (real connect-iq-sdk-manager flow) ──
# The proprietary SDK is downloaded behind a Garmin login. Flow per the CLI
# docs: agreement accept → login → sdk set → device download. Login creds come
# from GARMIN_USERNAME/GARMIN_PASSWORD (vault locally, GHA secrets in CI); when
# unset the CLI falls back to interactive OAuth (desktop only). The session +
# SDK are cached by the CLI, so this is idempotent across runs.
ciq() { in_nix connect-iq-sdk-manager "$@"; }

ensure_sdk() {
  local design="$1"
  local ver hash
  ver="$(bj '.toolchain.ciq_sdk')"
  hash="$(bj '.toolchain.agreement_hash')"

  # 1. Accept the SDK agreement — PUBLIC + non-interactive (the CLI fetches the
  #    current agreement and stores its hash). An optional pinned hash in
  #    build.json is validated against the current one; empty = accept current.
  if [ -n "$hash" ] && [ "$hash" != "null" ]; then
    ciq agreement accept --acceptance-hash="$hash"
  else
    ciq agreement accept
  fi
  # 2. Download + select the SDK — PUBLIC, no login (developer.garmin.com CDN).
  #    After this, monkeyc/connectiq/monkeydo exist under `sdk current-path`.
  ciq sdk set "$ver"
  # 3. Garmin login — required ONLY for the authenticated device-definition
  #    download below. Creds via GARMIN_USERNAME/GARMIN_PASSWORD (vault locally,
  #    GHA secrets in CI); interactive OAuth fallback on a desktop with no env.
  ciq login
  # 4. Per-design device definitions (AUTHENTICATED endpoint — needs step 3).
  #    monkeyc cannot compile for fenix8pro47mm without these.
  ciq device download --manifest="$FACES_DIR/$design/manifest.xml"
}

# Absolute path to the active SDK's bin/ (monkeyc, connectiq, monkeydo, …).
ciq_bin() { ciq sdk current-path --bin; }

# ── Design resolution ──────────────────────────────────────────────
resolve_design() {
  local d="${1:-}"
  if [ -z "$d" ]; then d="$(bj '.build.default_design')"; fi
  [ -d "$FACES_DIR/$d" ] || die "unknown design '$d' (faces/$d not found)"
  printf '%s' "$d"
}

# ── Generate data-driven artifacts (DesignConfig.mc + launcher icon) ─
step_gen() {
  local design; design="$(resolve_design "${1:-}")"
  log "gen: $design ← design.json"
  in_nix python3 "$LIB_DIR/gen-design.py" "$FACES_DIR/$design"
}

# ── Developer signing key (Pillar 7: sops from vault, never inline) ──
# Connect IQ signs with an RSA key in PKCS#8 DER. One stable key (in vault)
# signs every design so re-publishes keep a constant signature. Falls back to
# a locally-generated dev key ONLY for local sim when vault is unavailable.
_resolve_signing() {
  mkdir -p "$DIST_DIR"
  local keyname; keyname="$(bj '.signing.key_basename')"
  local keyout="$DIST_DIR/$keyname"
  [ -f "$keyout" ] && { printf '%s' "$keyout"; return 0; }

  local vault_dir="${VAULT_DIR:-$HOME/git/cloud-vault}"
  local vault_secrets; vault_secrets="$(bj '.signing.vault_secrets')"
  local enc="$vault_dir/$vault_secrets"

  if [ -f "$enc" ]; then
    log "signing: sops-decrypt $vault_secrets → dist/$keyname" >&2
    in_nix sops -d --extract '["developer_key_der_base64"]' "$enc" \
      | base64 -d > "$keyout" || die "sops decrypt of dev key failed"
  else
    errlog "signing: vault key absent ($enc) — generating LOCAL dev key (sim only, NOT for release)" >&2
    in_nix sh -c "openssl genrsa -out '$DIST_DIR/developer_key.pem' 4096 2>/dev/null \
      && openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt \
           -in '$DIST_DIR/developer_key.pem' -out '$keyout'" \
      || die "local dev-key generation failed"
  fi
  printf '%s' "$keyout"
}

step_secrets() { _resolve_signing >/dev/null; log "secrets: dist/$(bj '.signing.key_basename') ready"; }

# ── Compile ─────────────────────────────────────────────────────────
# .prg  = single-device sideload/CI artifact (device = build.json sim_default)
# .iq   = store package across all manifest products (monkeyc -e)
_compile() {
  local design="$1" mode="$2"            # mode: prg | iq
  step_gen "$design"
  ensure_sdk "$design"
  local key; key="$(_resolve_signing)"
  local mc; mc="$(ciq_bin)/monkeyc"
  local out="$DIST_DIR/$design"; mkdir -p "$out"
  local jungle="$FACES_DIR/$design/monkey.jungle"

  if [ "$mode" = "iq" ]; then
    local art="$out/$design.iq"
    log "package: monkeyc -e → $art"
    in_nix "$mc" -e -o "$art" -f "$jungle" -y "$key" -w -r
  else
    local device; device="$(bj '.devices.sim_default')"
    local art="$out/$design.prg"
    log "build: monkeyc -d $device → $art"
    in_nix "$mc" -o "$art" -f "$jungle" -y "$key" -d "$device" -w -r
  fi
}

step_sdk() { local d; d="$(resolve_design "${1:-}")"; ensure_sdk "$d"; log "sdk: ready ($(ciq_bin))"; }

step_build()   { local d; d="$(resolve_design "${1:-}")"; _compile "$d" prg; }
step_package() { local d; d="$(resolve_design "${1:-}")"; _compile "$d" iq; }

step_sim() {
  local design; design="$(resolve_design "${1:-}")"
  step_build "$design"
  local device bin; device="$(bj '.devices.sim_default')"; bin="$(ciq_bin)"
  log "sim: launching connectiq simulator ($device)"
  in_nix sh -c "'$bin/connectiq' & sleep 4; '$bin/monkeydo' '$DIST_DIR/$design/$design.prg' '$device'"
}

# ── Release plumbing (data-driven from build.json::release) ─────────
_version() { dj "$1" '.version // "0.1.0"' 2>/dev/null || printf '0.1.0'; }
_sha()     { printf '%s' "${GITHUB_SHA:-$(git -C "$SCRIPT_DIR" rev-parse --short HEAD 2>/dev/null || echo local)}"; }

# Expand {design}/{sha}/{version} placeholders in a release string.
_expand() {
  local s="$1" design="$2"
  s="${s//\{design\}/$design}"
  s="${s//\{sha\}/$(_sha)}"
  s="${s//\{version\}/$(_version "$design")}"
  printf '%s' "$s"
}

step_oras_push() {
  local design; design="$(resolve_design "${1:-}")"
  [ "$(bj '.release.ghcr.enabled')" = "true" ] || { log "oras-push: disabled — skip"; return 0; }
  local registry namespace image media art
  registry="$(bj '.release.ghcr.registry')"
  namespace="$(bj '.release.ghcr.namespace')"
  image="$(_expand "$(bj '.release.ghcr.image')" "$design")"
  media="$(bj '.release.ghcr.media_type')"
  art="$(_expand "$(bj '.release.artifact.prg')" "$design")"
  local artdir="$DIST_DIR/$design"
  [ -f "$artdir/$art" ] || die "oras-push: $artdir/$art missing — run build first"

  local tag; while IFS= read -r tag; do
    tag="$(_expand "$tag" "$design")"
    local ref="$registry/$namespace/$image:$tag"
    log "oras push $ref ← $art"
    ( cd "$artdir" && in_nix oras push "$ref" "$art:$media" )
  done < <(jqr -r '.release.ghcr.tags[]' "$BUILD_JSON")
}

step_oras_pull() {
  local design; design="$(resolve_design "${1:-}")"
  local tag="${2:-latest}"
  local registry namespace image
  registry="$(bj '.release.ghcr.registry')"
  namespace="$(bj '.release.ghcr.namespace')"
  image="$(_expand "$(bj '.release.ghcr.image')" "$design")"
  local out="$DIST_DIR/$design"; mkdir -p "$out"
  log "oras pull $registry/$namespace/$image:$tag → $out"
  ( cd "$out" && in_nix oras pull "$registry/$namespace/$image:$tag" )
}

step_gh_release() {
  local design; design="$(resolve_design "${1:-}")"
  [ "$(bj '.release.gh_release.enabled')" = "true" ] || { log "gh-release: disabled — skip"; return 0; }
  command -v gh >/dev/null 2>&1 || die "gh CLI not found"
  local rolling asset art
  rolling="$(_expand "$(bj '.release.gh_release.rolling_tag')" "$design")"
  asset="$(_expand "$(bj '.release.gh_release.asset_name')" "$design")"
  art="$(_expand "$(bj '.release.artifact.prg')" "$design")"
  local src="$DIST_DIR/$design/$art"
  [ -f "$src" ] || die "gh-release: $src missing — run build first"

  # Rolling release = stable download URL (…/releases/download/<rolling>/<asset>).
  if ! gh release view "$rolling" >/dev/null 2>&1; then
    log "gh-release: creating rolling release $rolling"
    gh release create "$rolling" --title "$design (latest)" \
      --notes "Rolling latest build of the $design watch face." \
      $( [ "$(bj '.release.gh_release.prerelease')" = "true" ] && echo --prerelease )
  fi
  log "gh-release: upload $asset → $rolling"
  gh release upload "$rolling" "$src#$asset" --clobber
}

step_clean() {
  local design="${1:-}"
  if [ -z "$design" ]; then
    log "clean: all designs + dist/"
    rm -rf "$DIST_DIR"
    find "$FACES_DIR" -type d -name gen -exec rm -rf {} + 2>/dev/null || true
    find "$FACES_DIR" -path '*/resources/drawables/launcher_icon.png' -delete 2>/dev/null || true
  else
    design="$(resolve_design "$design")"
    log "clean: $design"
    rm -rf "${DIST_DIR:?}/$design" "$FACES_DIR/$design/source/gen"
    rm -f "$FACES_DIR/$design/resources/drawables/launcher_icon.png"
  fi
}

step_shell() { command -v nix >/dev/null 2>&1 || die "nix not on PATH"; exec nix develop "$SCRIPT_DIR"; }

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; }

CMD="${1:-help}"; shift || true
case "$CMD" in
  gen)        step_gen        "${1:-}" ;;
  sdk)        step_sdk        "${1:-}" ;;
  build)      step_build      "${1:-}" ;;
  package)    step_package    "${1:-}" ;;
  sim)        step_sim        "${1:-}" ;;
  secrets)    step_secrets ;;
  oras-push)  step_oras_push  "${1:-}" ;;
  oras-pull)  step_oras_pull  "${1:-}" "${2:-}" ;;
  gh-release) step_gh_release "${1:-}" ;;
  clean)      step_clean      "${1:-}" ;;
  shell)      step_shell ;;
  help|-h|--help) usage ;;
  *) errlog "unknown command: $CMD"; usage; exit 1 ;;
esac
