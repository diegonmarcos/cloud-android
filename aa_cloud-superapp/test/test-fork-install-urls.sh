#!/usr/bin/env bash
# Tester: the 4 standalone comms fork-apps publish a stable rolling-'latest'
# GitHub release asset, and the SuperApp's extapp install URLs point at it.
#
# Chain under test (data-driven, one source of truth per link):
#   ea_cloud-<fork>/build.json::release.gh_release  (rolling_tag=latest, asset)
#     → ship-cloud-<fork>.yml calls `build.sh gh-release-fork`  (uploads asset
#       to the shared /releases/latest release, same as the other ea_cloud-* apps)
#     → ea_cloud-superapp build.json ui.external_apps[cloud-<fork>].install_apk_url
#       == https://.../releases/latest/download/<asset>
# So a tap on a not-installed comms tile does a one-tap install from a stable URL
# instead of the "not installed" snack.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → ea_cloud-superapp
UNIX="$(cd "$APP/.." && pwd)"                      # → ~/git/unix
BJ="$APP/build.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

# fork key → superapp external_apps id
FORKS="dialer:cloud-dialer chat:cloud-chat mail:cloud-mail matrix:cloud-matrix"
REL="releases/latest/download"

echo "== T1: each fork build.json declares a rolling-'latest' gh_release =="
for pair in $FORKS; do
  key="${pair%%:*}"; fbj="$UNIX/ea_cloud-$key/build.json"
  asset="$(jq -r '.release.gh_release.asset_name // ""' "$fbj" 2>/dev/null)"
  tag="$(jq -r '.release.gh_release.rolling_tag // ""' "$fbj" 2>/dev/null)"
  en="$(jq -r '.release.gh_release.enabled // false' "$fbj" 2>/dev/null)"
  [ "$tag" = "latest" ] && [ "$en" = "true" ] && [ "$asset" = "cloud-comms-$key.apk" ] \
    && ok "ea_cloud-$key: gh_release enabled, rolling_tag=latest, asset=$asset" \
    || bad "ea_cloud-$key: gh_release missing/wrong (tag=$tag enabled=$en asset=$asset)"
done

echo "== T2: each ship workflow uploads via the engine (build.sh gh-release-fork) =="
for pair in $FORKS; do
  key="${pair%%:*}"; wf="$UNIX/1_cicd/src/cicd/ship-cloud-$key.yml"
  grep -qF 'build.sh gh-release-fork' "$wf" 2>/dev/null \
    && ok "ship-cloud-$key.yml calls gh-release-fork" || bad "ship-cloud-$key.yml missing gh-release-fork step"
  # deployed copy must match (engine deploys src/cicd → .github/workflows)
  grep -qF 'build.sh gh-release-fork' "$UNIX/.github/workflows/ship-cloud-$key.yml" 2>/dev/null \
    && ok ".github ship-cloud-$key.yml in sync" || bad ".github ship-cloud-$key.yml NOT redeployed (run 9_others/build.sh)"
done

echo "== T3: the fork engine actually implements gh-release-fork =="
grep -qF 'step_gh_release_fork()' "$UNIX/ea_cloud-dialer/build.sh" 2>/dev/null \
  && ok "fork build.sh has step_gh_release_fork" || bad "fork build.sh missing step_gh_release_fork"
grep -qE 'gh-release-fork\)\s+step_gh_release_fork' "$UNIX/ea_cloud-dialer/build.sh" 2>/dev/null \
  && ok "gh-release-fork verb dispatched" || bad "gh-release-fork verb not wired in case"

echo "== T4: SuperApp install_apk_url == the fork's rolling-'latest' asset URL =="
for pair in $FORKS; do
  key="${pair%%:*}"; eid="${pair##*:}"
  want="https://github.com/diegonmarcos/unix/$REL/cloud-comms-$key.apk"
  got="$(jq -r --arg i "$eid" '.ui.external_apps[] | select(.id==$i) | .install_apk_url' "$BJ" 2>/dev/null)"
  [ "$got" = "$want" ] && ok "$eid install_apk_url → cloud-comms-$key.apk" \
    || bad "$eid install_apk_url mismatch (got: $got)"
done

echo "== T5: no install_apk_url is left blank for the comms apps =="
blank="$(jq -r '.ui.external_apps[] | select(.id|startswith("cloud-")) | select(.package|startswith("com.diegonmarcos.comms")) | select(.install_apk_url=="") | .id' "$BJ" 2>/dev/null)"
[ -z "$blank" ] && ok "no comms external_app has a blank install_apk_url" || bad "blank install_apk_url: $blank"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
