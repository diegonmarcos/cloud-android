#!/usr/bin/env bash
# Cloud Libs — Build Dispatcher
#
# One APK per constellation library module. Mirrors ea_cloud-keyboard-libs, but
# that repo produces a single bundle APK and this one produces N, so every step
# here loops over the SAME scan that settings.gradle uses (build.json::lib_apks).
# Nothing in this file names a module.
set -euo pipefail

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

_bj() { python3 -c "import json,sys;print(json.load(open('$SCRIPT_DIR/build.json'))$1)" 2>/dev/null; }

# The one scan, shared by settings.gradle / app/build.gradle / regen.sh.
# Emits: "<module>|<flavorName>|<Asset-Name.apk>|<ghcr-image>" per line.
_libs() {
  python3 - "$SCRIPT_DIR" <<'PY'
import json, os, sys
root = sys.argv[1]
cfg  = json.load(open(os.path.join(root, 'build.json')))
lc   = cfg['lib_apks']
scan = os.path.normpath(os.path.join(root, lc['scan']))
excl = set(lc.get('exclude', {}))
img  = cfg['release']['ghcr']['image_prefix']
names = sorted(d for d in os.listdir(scan)
               if os.path.isfile(os.path.join(scan, d, 'build.gradle')) and d not in excl)
if not names:
    sys.exit(f"FATAL: no library modules under {scan}")
for n in names:
    parts  = n.split('-')
    flavor = parts[0] + ''.join(p.capitalize() for p in parts[1:])
    asset  = lc['asset_prefix'] + '-'.join(p.capitalize() for p in parts) + '.apk'
    print(f"{n}|{flavor}|{asset}|{img}{n}")
PY
}

# ONE shared Cloud-constellation signing key — identical to every other app in
# the constellation. NO fallback: a debug-signed APK is a fresh random key per CI
# run, which both breaks updates and silently drops the APK out of the signature-
# level CONSTELLATION_DATA grant.
_resolve_signing() {
  if [ -n "${GITHUB_ACTIONS:-}${CI:-}" ] \
     && [ -n "${ANDROID_KEYSTORE_FILE:-}" ] && [ -f "${ANDROID_KEYSTORE_FILE}" ] && [ -n "${ANDROID_KEY_ALIAS:-}" ]; then
    log "signing: using pre-set ANDROID_KEYSTORE_* (CI secret delivery)"; return 0
  fi
  local ks_rel sec_rel vault ks store_pw key_pw alias_
  ks_rel="$(_bj "['signing']['vault_keystore']")"
  sec_rel="$(_bj "['signing']['vault_secrets']")"
  if [ -z "$ks_rel" ] || [ -z "$sec_rel" ]; then
    errlog "FATAL signing: build.json::signing.vault_keystore/.vault_secrets are empty."
    exit 1
  fi
  vault="${VAULT_DIR:-$HOME/git/cloud-vault}"
  ks="$vault/$ks_rel"
  [ -f "$ks" ] || { errlog "FATAL signing: shared keystore missing at $ks (check out vault / set VAULT_DIR). No fallback."; exit 1; }
  command -v sops >/dev/null 2>&1 || { errlog "FATAL signing: sops not on PATH; cannot decrypt the shared key. Refusing to build."; exit 1; }
  store_pw="$(sops --config /dev/null -d --extract '["keystore_password"]' "$vault/$sec_rel" 2>/dev/null || true)"
  key_pw="$(sops --config /dev/null -d --extract '["key_password"]'      "$vault/$sec_rel" 2>/dev/null || true)"
  alias_="$(sops  --config /dev/null -d --extract '["key_alias"]'        "$vault/$sec_rel" 2>/dev/null || true)"
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

_enforce_signature() {
  local apk="$1" bt zipalign apksigner
  [ -f "$apk" ] || { errlog "sign-enforce: missing APK $apk"; exit 1; }
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
  log "sign-enforce: OK $(basename "$apk")"
}

# $1 = debug|release. Assembles every flavor in ONE gradle invocation (the
# configuration phase dominates here, so 24 separate invocations would cost
# minutes for nothing) and collects each flavor's APK under its asset name.
_assemble() {
  local variant="$1" tasks=() n flavor asset image cap
  mkdir -p "$DIST_DIR"
  while IFS='|' read -r n flavor asset image; do
    cap="$(printf '%s' "${flavor:0:1}" | tr '[:lower:]' '[:upper:]')${flavor:1}"
    tasks+=(":app:assemble${cap}$( [ "$variant" = release ] && echo Release || echo Debug )")
  done < <(_libs)
  log "Assembling ${#tasks[@]} library APKs ($variant)…"
  _gradle "${tasks[@]}"

  local out count=0
  while IFS='|' read -r n flavor asset image; do
    out="$(find "$SCRIPT_DIR/app/build/outputs/apk/$flavor/$variant" -name '*.apk' -print -quit 2>/dev/null || true)"
    [ -n "$out" ] || { errlog "no APK produced for module $n (flavor $flavor, $variant)"; exit 1; }
    cp -f "$out" "$DIST_DIR/$asset"
    _enforce_signature "$DIST_DIR/$asset"
    count=$((count + 1))
  done < <(_libs)
  log "$count library APKs → $DIST_DIR/"
}

case "$CMD" in
  build)
    log "Building Cloud Libs APKs (debug)…"
    _resolve_signing
    _assemble debug
    ;;
  release)
    log "Building Cloud Libs APKs (release)…"
    _resolve_signing
    _assemble release
    ;;
  clean)
    _gradle clean
    rm -rf "$DIST_DIR"
    ;;
  oras-push)
    log "Pushing library APKs to GHCR via ORAS…"
    SHA="${GITHUB_SHA:-$(git -C "$SCRIPT_DIR" rev-parse HEAD)}"
    SHORT="${SHA:0:8}"
    reg="$(_bj "['release']['ghcr']['registry']")/$(_bj "['release']['ghcr']['namespace']")"
    mt="$(_bj "['release']['ghcr']['media_type']")"
    while IFS='|' read -r n flavor asset image; do
      ( cd "$DIST_DIR" && oras push "${reg}/${image}:latest"       "${asset}:${mt}" )
      ( cd "$DIST_DIR" && oras push "${reg}/${image}:sha-${SHORT}" "${asset}:${mt}" )
      log "pushed ${image}:latest + :sha-${SHORT}"
    done < <(_libs)
    ;;
  gh-release)
    log "Publishing library APKs to GitHub Releases (rolling latest)…"
    # One upload call with every asset: `gh release upload` takes N files, and a
    # per-file loop would re-resolve the release 24 times.
    mapfile -t files < <(_libs | cut -d'|' -f3 | sed "s#^#$DIST_DIR/#")
    gh release upload latest "${files[@]}" --clobber 2>/dev/null \
      || gh release create latest \
           --title "Cloud Libs (rolling)" \
           --notes "Auto-updated from main." \
           "${files[@]}"
    log "published ${#files[@]} assets"
    ;;
  list)
    # Same scan the build uses — handy for confirming what will ship.
    _libs | column -t -s'|'
    ;;
  help|*)
    echo "Usage: build.sh <build|release|clean|oras-push|gh-release|list>"
    ;;
esac
