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
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"   # → ~/git/cloud-android
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has()   { grep -qF "$2" "$ROOT/$1" 2>/dev/null && ok "$3" || bad "$3 ($1)"; }
hasnt() { grep -qF "$2" "$ROOT/$1" 2>/dev/null && bad "$3 ($1)" || ok "$3"; }

# The four vendored Fleet.kt copies were replaced by ONE shared module in
# ab_cloud-libs-shared that every app compiles against. The old "4 copies are
# byte-identical" invariant is now structural rather than checked, so T3
# asserts the copies have not crept back instead of comparing checksums.
REL="libs/updater/src/main/java/com/diegonmarcos/superapp/updater/Fleet.kt"
FLEET="ab_cloud-libs-shared/$REL"

echo "== T1: ABI-tag no longer composes the bogus '<tag>-<deviceAbi>' =="
hasnt "$FLEET" '${app.tag}-$abi' "dropped the always-404 <tag>-<abi> composition"
has   "$FLEET" '${app.tag}-x86_64' "x86_64-gated suffix"
has   "$FLEET" 'Build.SUPPORTED_ABIS.firstOrNull() == "x86_64"' "suffix only attempted on x86_64"

echo "== T2: self entry short-circuits on manifest revision (no phantom update) =="
has "$FLEET" 'app.pkg == ctx.packageName' "identifies the self entry"
has "$FLEET" 'layer.revision == BuildConfig.GIT_SHORT_SHA' "compares GHCR revision to built-in git sha"

echo "== T3: exactly one Fleet.kt — no vendored copies crept back =="
n=$(ls -1 "$ROOT"/*/"$REL" 2>/dev/null | wc -l)
[ "$n" = "1" ] && ok "single shared Fleet.kt" || bad "expected 1 Fleet.kt, found $n"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
