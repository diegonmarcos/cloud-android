#!/usr/bin/env bash
# Build the firestack netstack aar that libs:firewall consumes.
#
# This lives WITH the module, not inside one consumer, because libs:firewall has
# two of them: aa_cloud-superapp (which compiles it into Cloud-SuperApp.apk) and
# ab_cloud-libs-shared/lib-apks (which ships it as Cloud-Lib-Firewall.apk). The
# build step used to exist only in the superapp's build.sh, so lib-apks could
# never produce the aar — which is why the dependency could not be declared in
# libs/firewall/build.gradle without breaking ship-cloud-libs. One script, both
# callers, no duplicated Go/gomobile logic to drift.
#
# ENV-AGNOSTIC BY DESIGN: it assumes it is ALREADY running wherever ANDROID_HOME
# and an NDK are visible. Each caller wraps it in its own devShell
# (`in_nix bash build-firestack.sh`), because the superapp resolves that through
# its flake while lib-apks runs under BYPASS_NIX=1 with the SDK the CI action
# installed. Baking either assumption in here would break the other.
#
# Idempotent: exits 0 immediately if the aar is already present.
#
# Usage:  build-firestack.sh [--force]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"          # libs/firewall
SHARED_ROOT="$(cd "$HERE/../.." && pwd)"                       # ab_cloud-libs-shared
CFG="$SHARED_ROOT/build.json"
SRC="$HERE/firestack"                                          # vendored source AND aar home

log()    { printf '  \033[36m•\033[0m %s\n' "$*"; }
errlog() { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; }

command -v jq >/dev/null 2>&1 || { errlog "firestack: jq is required"; exit 1; }
[ -f "$CFG" ] || { errlog "firestack: no $CFG"; exit 1; }

cfgv() { jq -r "$1 // empty" "$CFG"; }

AAROUT="$(cfgv '.firestack.build.aar_out')"
[ -n "$AAROUT" ] || { errlog "firestack: build.json has no .firestack.build.aar_out"; exit 1; }
AAR="$SRC/$AAROUT"

if [ "${1:-}" != "--force" ] && [ -f "$AAR" ]; then
  log "firestack: aar already present ($AAR) — skipping"
  exit 0
fi

GOVER="$(cfgv '.firestack.build.go_version')"
GOSHA="$(cfgv '.firestack.build.go_sha256_linux_amd64')"
TARGET="$(cfgv '.firestack.build.make_target')"
AARBUILT="$(cfgv '.firestack.build.aar_built')"
API="$(cfgv '.firestack.build.android_api')"
TAGS="$(cfgv '.firestack.build.gomobile_tags')"
VARIANT="${SUPERAPP_VARIANT:-}"
GT="$(jq -r --arg v "$VARIANT" \
      '.firestack.build.gomobile_targets[$v] // .firestack.build.gomobile_targets[""] // empty' "$CFG")"

# Vendored tree: no .git, so go.mod is the marker that the source is usable.
# Testing for .git here would be false forever and re-clone over committed code.
[ -f "$SRC/go.mod" ] || { errlog "firestack: vendored source missing at $SRC (expected go.mod)"; exit 1; }

# The pinned Go tarball + sha in build.json are linux/amd64 (the x86 CI runner).
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) : ;;
  *) errlog "firestack: pinned Go is linux-amd64 only (host: $(uname -s)-$(uname -m)). Build on the x86 runner, or add this arch's tarball+sha to build.json::firestack.build."; exit 1 ;;
esac

[ -n "${ANDROID_HOME:-}" ] || { errlog "firestack: ANDROID_HOME unset — call this inside the devShell / after setup-android"; exit 1; }

CACHE="${FIRESTACK_CACHE:-$SHARED_ROOT/.cache}"
GODIR="$CACHE/golang"
export GOPATH="$CACHE/gopath" GOBIN="$CACHE/gopath/bin" GOTOOLCHAIN=local
mkdir -p "$GODIR" "$GOPATH"

TARBALL="go${GOVER}.linux-amd64.tar.gz"
if [ ! -x "$GODIR/go/bin/go" ]; then
  log "firestack: downloading Go $GOVER"
  curl -fLso "$GODIR/$TARBALL" "https://go.dev/dl/$TARBALL"
  echo "$GOSHA  $GODIR/$TARBALL" | sha256sum -c -
  rm -rf "$GODIR/go"; tar -C "$GODIR" -xzf "$GODIR/$TARBALL"
fi
export PATH="$GODIR/go/bin:$GOBIN:$PATH"

# gomobile finds the NDK via ANDROID_NDK_HOME; take the newest present rather
# than pinning a version that the SDK action may stop installing.
NDK="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"
[ -n "$NDK" ] || { errlog "firestack: no NDK under $ANDROID_HOME/ndk"; exit 1; }
export ANDROID_NDK_HOME="$NDK" ANDROID_NDK_ROOT="$NDK"

log "firestack: building netstack aar — $(go version); ndk=$(basename "$NDK"); abi=$GT (slow)"
make -C "$SRC" clean || true
# Override firestack's Makefile ANDROID23 so gomobile builds ONE ABI, not all
# four: the default is ~4x the time and blows the CI job limit.
make -C "$SRC" "$TARGET" ANDROID23="-androidapi $API -target=$GT -tags=$TAGS -work"
cp "$SRC/$AARBUILT" "$AAR"

# Smoke-test: a truncated or empty aar resolves in gradle and fails at dex time,
# a long way from the cause.
[ -f "$AAR" ] || { errlog "firestack: aar not produced: $AAR"; exit 1; }
if command -v unzip >/dev/null 2>&1; then
  unzip -l "$AAR" | grep -q "classes.jar"         || { errlog "firestack: aar has no classes.jar"; exit 1; }
  unzip -l "$AAR" | grep -q "AndroidManifest.xml" || { errlog "firestack: aar has no AndroidManifest.xml"; exit 1; }
else
  log "firestack: unzip absent — skipping aar smoke-test"
fi

log "firestack: → $AAR ($(du -h "$AAR" 2>/dev/null | cut -f1))"
