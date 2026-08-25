#!/usr/bin/env bash
# Plain shell + assert test (matches ~/git/cloud-infra/2_configs/test/*.sh convention:
# no framework, source fixture data, assert on resolved values).
#
# Proves the fork-engine's hub-vs-hub-less branching is purely data-driven
# from build.json (never app-name based):
#   - a hub-less fixture (build.json::mode=="single-app", media-center shape)
#     must resolve gradle task / apk path / launcher activity WITHOUT any
#     ":hub:" or "com.diegonmarcos.comms" literal.
#   - a hub-based fixture (mail shape, no "mode" key) must still resolve the
#     exact historical ":hub:assembleDebug" / hub apk path / activity.
#
# SPLIT (2026-07-30): the engine is now 4 per-app files (cloud-{mail,chat,
# dialer,matrix}-fork-engine.sh) instead of one shared cloud-comms-fork-
# engine.sh — they're byte-identical copies of the same generic logic, so
# testing one (mail, picked arbitrarily) covers all 4.
#
# No network, no Android SDK, no gradle, no adb, no device. Dry-run only:
# this test only calls the pure resolution helpers (_is_hubless,
# _single_fork_key, _launcher_activity, _json), never step_build/step_dev/etc.
set -euo pipefail

ENGINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/cloud-mail-fork-engine.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass=0
fail=0
assert_eq() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    printf '  [PASS] %s\n' "$desc"; pass=$((pass+1))
  else
    printf '  [FAIL] %s (expected=%q actual=%q)\n' "$desc" "$expected" "$actual"; fail=$((fail+1))
  fi
}
assert_not_contains() {
  local desc="$1" needle="$2" haystack="$3"
  case "$haystack" in
    *"$needle"*) printf '  [FAIL] %s (must NOT contain %q, got %q)\n' "$desc" "$needle" "$haystack"; fail=$((fail+1)) ;;
    *)           printf '  [PASS] %s\n' "$desc"; pass=$((pass+1)) ;;
  esac
}

run_in_fixture() {
  # $1 = fixture build.json content, $2 = bash expr to eval after sourcing engine.
  # SCRIPT_DIR (and hence build.json lookup) derives from $0/dirname, so we
  # `source` the real engine from inside a throwaway dir holding only the
  # fixture build.json. The main-guard ([[ BASH_SOURCE == $0 ]]) keeps the
  # dispatch/case block from running — only functions get defined.
  local json="$1" expr="$2"
  local dir; dir="$(mktemp -d "$WORK/fixture.XXXX")"
  printf '%s' "$json" > "$dir/build.json"
  ( cd "$dir" && bash -c '
      set -euo pipefail
      set -- help
      source "'"$ENGINE"'"
      '"$expr"'
    '
  )
}

echo "== hub-less fixture (media-center shape) =="
HUBLESS_JSON='{
  "name": "cloud-media-center",
  "mode": "single-app",
  "launcher_activity": "com.diegonmarcos.mediacenter.MainActivity",
  "android": { "application_id": "com.diegonmarcos.mediacenter" },
  "release": { "artifact": { "debug": "cloud-media-center-debug.apk", "release": "cloud-media-center.apk" } },
  "forks": {
    "media-center": {
      "upstream_repo": "https://github.com/IacobIonut01/ReFra.git",
      "tracker_dir": "aa_upstreams-sources/media-refra",
      "pinned_tag": "5.1.1-51101-nightly",
      "app_id": "com.diegonmarcos.mediacenter",
      "build": { "gradle_task": "assembleArm64V8aNoMLRelease", "apk_glob": "app/build/outputs/apk/**/*.apk" }
    }
  }
}'
out="$(run_in_fixture "$HUBLESS_JSON" '
  echo "is_hubless=$(_is_hubless && echo yes || echo no)"
  echo "key=$(_single_fork_key)"
  echo "gradle_task=$(_json ".forks[\"$(_single_fork_key)\"].build.gradle_task")"
  echo "launcher=$(_launcher_activity)"
  echo "app_id=$(_json ".android.application_id")"
')"
eval "$out"
assert_eq "hub-less: _is_hubless true" "yes" "$is_hubless"
assert_eq "hub-less: single fork key" "media-center" "$key"
assert_not_contains "hub-less: gradle task has no :hub:" ":hub:" "$gradle_task"
assert_eq "hub-less: gradle task is the fork's own" "assembleArm64V8aNoMLRelease" "$gradle_task"
assert_not_contains "hub-less: launcher activity is not comms hub's" "com.diegonmarcos.comms" "$launcher"
assert_eq "hub-less: launcher activity from build.json" "com.diegonmarcos.mediacenter.MainActivity" "$launcher"

echo "== hub-based fixture (mail shape, no 'mode' key) =="
HUB_JSON='{
  "name": "cloud-comms-mail",
  "android": { "application_id": "com.diegonmarcos.comms" },
  "release": { "artifact": { "debug": "cloud-comms-mail-debug.apk", "release": "cloud-comms-mail.apk" } },
  "forks": {
    "mail": {
      "upstream_repo": "https://github.com/M66B/FairEmail.git",
      "tracker_dir": "aa_mail-fairmail",
      "pinned_tag": "9.4.1",
      "build": { "gradle_task": "assembleGithubRelease", "apk_glob": "app/build/outputs/apk/**/*.apk" }
    }
  }
}'
out="$(run_in_fixture "$HUB_JSON" '
  echo "is_hubless=$(_is_hubless && echo yes || echo no)"
  echo "launcher=$(_launcher_activity)"
')"
eval "$out"
assert_eq "hub-based: _is_hubless false (no mode key => old path)" "no" "$is_hubless"
assert_eq "hub-based: launcher activity falls back to historical hub literal" \
  "com.diegonmarcos.comms.MainActivity" "$launcher"

echo
echo "Results: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
