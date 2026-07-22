#!/usr/bin/env bash
# Tester: no unresolved git merge/stash conflict markers in SuperApp source.
#
# Commit 598eb1c6 committed stash-pop conflict markers straight into
# app/build.gradle (around the libs:zoomies / libs:devcontrol deps). Groovy
# can't parse `<<<<<<<`/`=======`/`>>>>>>>`, so every ship-cloud-superapp build
# died at configuration ("Unexpected input: '{'" at `dependencies {`). This
# guards against that whole class: a conflict marker in any buildable source
# file fails here BEFORE it wastes a CI build.
#
# Matches git's exact marker forms only (7 chars): `<<<<<<< `, `>>>>>>> `,
# a lone `=======`, and the diff3 `||||||| ` base line — so decorative rules
# like an 18-'=' divider in a CREDITS.txt do NOT false-positive.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → ea_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

# Buildable source only; skip generated build/, logs, and text assets.
HITS="$(rg -n --glob '!**/build/**' --glob '!**/*.log' \
  -g '*.kt' -g '*.java' -g '*.gradle' -g '*.xml' -g '*.json' -g '*.yml' -g '*.yaml' -g '*.pro' -g '*.aidl' \
  -e '^<<<<<<< ' -e '^>>>>>>> ' -e '^=======$' -e '^\|\|\|\|\|\|\| ' \
  "$APP" 2>/dev/null)"

echo "== T1: no git conflict markers in SuperApp buildable source =="
if [ -z "$HITS" ]; then
  ok "no conflict markers in *.{kt,java,gradle,xml,json,yml,pro,aidl}"
else
  bad "conflict markers found:"
  echo "$HITS" | sed 's/^/      /'
fi

echo "== T2: app/build.gradle is brace-balanced (parses) =="
depth="$(awk '{for(i=1;i<=length($0);i++){c=substr($0,i,1);if(c=="{")d++;if(c=="}")d--}} END{print d+0}' "$APP/app/build.gradle")"
[ "$depth" = "0" ] && ok "app/build.gradle brace depth = 0" || bad "app/build.gradle unbalanced (depth=$depth)"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
