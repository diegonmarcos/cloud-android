#!/usr/bin/env bash
# Tester: Fleet (constellation AppStore) tag resolution + self-update short-circuit.
#
# Two defects this asserts are fixed, across all 4 superapp-lineage copies
# (superapp canonical + wallet/vault/browser vendored, kept byte-identical):
#
#   1. ABI-tag round-trip. The old remoteLayer composed "<tag>-<deviceAbi>",
#      i.e. "latest-arm64-v8a" — a tag that is NEVER published (arm64 is the
#      universal `<tag>`; only x86_64 gets a `-x86_64` suffix). Every arm64
#      device therefore paid a guaranteed 404 + fallback round-trip per app,
#      per check. Fix: only reach for the suffix on x86_64.
#
#   2. Phantom self-update. Builds are not byte-reproducible, so a same-commit
#      superapp rebuild has a different APK sha and Fleet.status flagged SELF as
#      UpdateAvailable forever. Fix: for the self entry, short-circuit on the
#      GHCR manifest revision == BuildConfig.GIT_SHORT_SHA (same signal the
#      self-updater UpdateChecker already uses).
#
# Static wiring tester (no device): asserts the exact source markers.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"   # → ~/git/unix
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has()   { grep -qF "$2" "$ROOT/$1" 2>/dev/null && ok "$3" || bad "$3 ($1)"; }
hasnt() { grep -qF "$2" "$ROOT/$1" 2>/dev/null && bad "$3 ($1)" || ok "$3"; }

APPS="ea_cloud-superapp ea_cloud-wallet ea_cloud-vault ea_cloud-browser"
REL="libs/updater/src/main/java/com/diegonmarcos/superapp/updater/Fleet.kt"

echo "== T1: ABI-tag no longer composes the bogus '<tag>-<deviceAbi>' =="
for a in $APPS; do
  f="$a/$REL"
  hasnt "$f" '${app.tag}-$abi' "$a: dropped the always-404 <tag>-<abi> composition"
  has   "$f" '${app.tag}-x86_64' "$a: x86_64-gated suffix"
  has   "$f" 'Build.SUPPORTED_ABIS.firstOrNull() == "x86_64"' "$a: suffix only attempted on x86_64"
done

echo "== T2: self entry short-circuits on manifest revision (no phantom update) =="
for a in $APPS; do
  f="$a/$REL"
  has "$f" 'app.pkg == ctx.packageName' "$a: identifies the self entry"
  has "$f" 'layer.revision == BuildConfig.GIT_SHORT_SHA' "$a: compares GHCR revision to built-in git sha"
done

echo "== T3: all 4 copies are byte-identical (vendored invariant) =="
n=$(md5sum $(for a in $APPS; do echo "$ROOT/$a/$REL"; done) 2>/dev/null | awk '{print $1}' | sort -u | wc -l)
[ "$n" = "1" ] && ok "Fleet.kt identical across all 4 apps" || bad "Fleet.kt diverged ($n distinct versions)"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
