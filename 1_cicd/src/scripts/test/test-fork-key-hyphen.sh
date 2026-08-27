#!/usr/bin/env bash
# Regression test: fork keys containing a hyphen must resolve in the engine's
# jq lookups.
#
# Bug (GHA run 30528694044, ac_cloud-media-center): step_build_fork built its
# jq filters by shell-interpolating the fork key into a jq PATH:
#     jq -r ".forks.${key}.build.prepare // [] | .[]"
# With key="media-center" jq parses that as arithmetic on undefined functions:
#     jq: error: center/0 is not defined
# It never fired for mail/chat/dialer/matrix because those keys are single
# words. Fix: pass the key as data (--arg) and index with brackets.
#
# Pure shell + jq. No gradle, no SDK, no network.
set -uo pipefail

PASS=0
FAIL=0

check() { # check <desc> <expected> <actual>
  if [[ "$2" == "$3" ]]; then
    printf 'ok   %s\n' "$1"; PASS=$((PASS + 1))
  else
    printf 'FAIL %s\n       expected: %q\n       actual:   %q\n' "$1" "$2" "$3"
    FAIL=$((FAIL + 1))
  fi
}

FIX=$(mktemp -d)
trap 'rm -rf "$FIX"' EXIT

cat >"$FIX/build.json" <<'JSON'
{
  "mode": "single-app",
  "forks": {
    "_doc": "underscore keys are metadata, not forks",
    "media-center": {
      "build": {
        "prepare": ["echo one", "echo two"],
        "gradle_props": {
          "_doc": "underscore keys must be skipped",
          "android.useAndroidX": "true",
          "org.gradle.jvmargs": "-Xmx4g"
        }
      }
    }
  }
}
JSON

key="media-center"

# --- the two expressions the engine actually uses (post-fix form) ------------
prepare=$(jq -r --arg k "$key" '.forks[$k].build.prepare // [] | .[]' "$FIX/build.json" | tr '\n' '|')
check "hyphenated key resolves build.prepare" "echo one|echo two|" "$prepare"

props=$(jq -r --arg k "$key" \
  '.forks[$k].build.gradle_props // {} | to_entries[] | select(.key | startswith("_") | not) | "\(.key)=\(.value)"' \
  "$FIX/build.json" | sort | tr '\n' '|')
check "hyphenated key resolves gradle_props, skips _doc" \
  "android.useAndroidX=true|org.gradle.jvmargs=-Xmx4g|" "$props"

# --- absent optional blocks must yield empty, not an error ------------------
missing=$(jq -r --arg k "nope" '.forks[$k].build.prepare // [] | .[]' "$FIX/build.json" 2>&1)
check "unknown key yields empty, no jq error" "" "$missing"

# --- the OLD form must genuinely fail, else this test proves nothing --------
if jq -r ".forks.${key}.build.prepare // [] | .[]" "$FIX/build.json" >/dev/null 2>&1; then
  check "old interpolated form is broken (guards against a vacuous test)" "fails" "succeeded"
else
  check "old interpolated form is broken (guards against a vacuous test)" "fails" "fails"
fi

# --- single-word keys must keep working (siblings mail/chat/dialer/matrix) --
cat >"$FIX/hub.json" <<'JSON'
{ "forks": { "mail": { "build": { "prepare": ["echo mail"] } } } }
JSON
sib=$(jq -r --arg k "mail" '.forks[$k].build.prepare // [] | .[]' "$FIX/hub.json")
check "single-word sibling key still resolves" "echo mail" "$sib"

# --- the engine source must not reintroduce an interpolated jq path ---------
# The shared cloud-comms-fork-engine.sh was retired 2026-07-30 — each
# ac_cloud-* app now carries its OWN byte-for-byte copy
# (cloud-{mail,chat,dialer,matrix,media-center}-fork-engine.sh). A fix applied
# to one copy does NOT propagate to its siblings, so this checks EVERY copy,
# not one hardcoded name (rule 6) — glob, not a literal app list.
SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mapfile -t ENGINES < <(printf '%s\n' "$SCRIPTS_DIR"/*-fork-engine.sh)
if [[ "${#ENGINES[@]}" -eq 0 ]]; then
  printf 'FAIL no *-fork-engine.sh found under %s\n' "$SCRIPTS_DIR"; FAIL=$((FAIL + 1))
fi
for ENGINE in "${ENGINES[@]}"; do
NAME="$(basename "$ENGINE")"
if [[ -f "$ENGINE" ]]; then
  # Count EXECUTABLE occurrences of an interpolated fork path. Comments and
  # human-readable error-message strings are excluded; a jq filter is not.
  #
  # An earlier version of this check only looked at two known call sites and a
  # bad regex reported "no others" while 29 remained (GHA 30529963919 failed
  # with the same jq error after the "fix"). It now scans the whole file.
  leaked=$(python3 - "$ENGINE" <<'PY'
import re, sys
bad = 0
for line in open(sys.argv[1], encoding='utf8'):
    s = line.strip()
    if s.startswith('#'):
        continue
    if '.forks.${' not in s:
        continue
    # errlog/printf/echo message text is prose, not a jq program
    if re.search(r'\b(errlog|log|printf|echo)\b', s):
        continue
    bad += 1
print(bad)
PY
)
  check "[$NAME] no executable interpolated .forks.\${...} jq path" "0" "$leaked"

  # Every fork read must go through the safe helper.
  helper=$(python3 -c "
import sys; print(open(sys.argv[1],encoding='utf8').read().count('_fork_json'))" "$ENGINE")
  [[ "$helper" -gt 0 ]] \
    && check "[$NAME] defines/uses _fork_json helper" "yes" "yes" \
    || check "[$NAME] defines/uses _fork_json helper" "yes" "no"
else
  printf 'FAIL engine not found at %s\n' "$ENGINE"; FAIL=$((FAIL + 1))
fi
done

# --- ABI-keyed gradle task resolution -------------------------------------
# Upstream ReFra dimensions productFlavors by ABI, so each ABI has its OWN
# assemble task and one fixed string cannot serve a multi-ABI matrix. Gradle
# capitalizes only the first letter of a flavor name, so "arm64-v8a" becomes
# "Arm64-v8a" -- the hyphen SURVIVES. GHA run 30535280370 proved it:
#   Task 'assembleArm64V8aNoMLRelease' not found ...
#   Some candidates are: 'assembleArm64-v8aNoMLRelease'
cat >"$FIX/abi.json" <<'JSON'
{
  "forks": {
    "media-center": {
      "build": {
        "gradle_task": "assembleArm64-v8aNoMLRelease",
        "gradle_task_by_abi": {
          "arm64-v8a": "assembleArm64-v8aNoMLRelease",
          "x86_64": "assembleX86_64NoMLRelease"
        }
      }
    }
  },
  "single": { "build": { "gradle_task": "assembleGithubRelease" } }
}
JSON

resolve() { # resolve <abi> <json> — mirrors the engine's two-step lookup
  local abi="$1" f="$2" t
  t=$(jq -r --arg k media-center --arg a "$abi" \
      '.forks[$k].build.gradle_task_by_abi[$a] // empty' "$f")
  [[ -n "$t" ]] || t=$(jq -r --arg k media-center '.forks[$k].build.gradle_task // empty' "$f")
  printf '%s' "$t"
}

check "arm64-v8a resolves its own task (hyphen preserved)" \
  "assembleArm64-v8aNoMLRelease" "$(resolve arm64-v8a "$FIX/abi.json")"
check "x86_64 resolves a DIFFERENT task" \
  "assembleX86_64NoMLRelease" "$(resolve x86_64 "$FIX/abi.json")"
check "unknown ABI falls back to .gradle_task" \
  "assembleArm64-v8aNoMLRelease" "$(resolve riscv64 "$FIX/abi.json")"

# the wrong name that actually failed in CI must not be produced for any ABI
for a in arm64-v8a x86_64 riscv64; do
  got="$(resolve "$a" "$FIX/abi.json")"
  case "$got" in
    *Arm64V8a*) check "ABI $a does not emit the CI-proven-wrong Arm64V8a form" "clean" "GOT:$got" ;;
    *)          check "ABI $a does not emit the CI-proven-wrong Arm64V8a form" "clean" "clean" ;;
  esac
done

# a sibling fork declaring only .gradle_task must be unaffected by the new key
sib2=$(jq -r '.single.build.gradle_task_by_abi["arm64-v8a"] // .single.build.gradle_task' "$FIX/abi.json")
check "fork without gradle_task_by_abi still resolves .gradle_task" \
  "assembleGithubRelease" "$sib2"

# --- signing_env token vocabulary ------------------------------------------
# signing=vault_jks_env maps upstream-chosen env var NAMES to tokens the engine
# resolves. A typo'd token is only discovered as an exit 1 mid-CI, so assert
# every token declared by any consumer is one the engine actually handles.
# Checked against EACH engine copy — a consumer's build.json is only
# guaranteed compatible with its OWN app's engine copy since the split.
for ENGINE in "${ENGINES[@]}"; do
NAME="$(basename "$ENGINE")"
if [[ -f "$ENGINE" ]]; then
  bad_tokens=$(python3 - "$ENGINE" <<'PY'
import glob, json, os, re, sys

engine = open(sys.argv[1], encoding='utf8').read()
# the case arms inside the vault_jks_env branch define the vocabulary
known = set(re.findall(r'^\s*(store_password|key_password|key_alias|keystore_path)\)',
                       engine, re.M))
root = os.path.abspath(os.path.join(os.path.dirname(sys.argv[1]), '..', '..', '..'))
bad = []
for bj in glob.glob(os.path.join(root, 'ac_cloud-*', 'build.json')):
    try:
        d = json.load(open(bj, encoding='utf8'))
    except Exception:
        continue
    for k, f in (d.get('forks') or {}).items():
        if k.startswith('_') or not isinstance(f, dict):
            continue
        b = f.get('build') or {}
        env = b.get('signing_env') or {}
        if env and b.get('signing') != 'vault_jks_env':
            bad.append(f"{os.path.basename(os.path.dirname(bj))}:{k} declares signing_env but signing={b.get('signing')!r}")
        for name, tok in env.items():
            if name.startswith('_'):
                continue
            if tok not in known:
                bad.append(f"{os.path.basename(os.path.dirname(bj))}:{k}:{name} -> unknown token {tok!r}")
        if b.get('signing') == 'vault_jks_env' and not b.get('keystore_dest'):
            bad.append(f"{os.path.basename(os.path.dirname(bj))}:{k} vault_jks_env without keystore_dest")
print('; '.join(bad) if bad else 'clean')
PY
)
  check "[$NAME] every signing_env token is known to the engine" "clean" "$bad_tokens"
fi
done

# --- apk_glob needs globstar ------------------------------------------------
# AGP writes to app/build/outputs/apk/<abiFlavor><ml>/<buildType>/*.apk — TWO
# levels below apk/. Bash only treats "**" as recursive when globstar is on;
# otherwise it degrades to a single-level "*" and the APK is never found. GHA
# 30539856374 compiled AND signed an APK, then failed with "no APK matched".
AGP="$FIX/tracker/app/build/outputs/apk/arm64-v8aNoML/release"
mkdir -p "$AGP"
: >"$AGP/ReFra-5.1.1-arm64-v8a-release.apk"
GLOB='app/build/outputs/apk/**/*.apk'

count_glob() { # count_glob <globstar on|off>
  ( if [[ "$1" == on ]]; then shopt -s nullglob globstar; else shopt -s nullglob; shopt -u globstar; fi
    local a=("$FIX/tracker"/$GLOB); printf '%d' "${#a[@]}" )
}
check "without globstar the two-level APK is NOT found (proves the bug)" "0" "$(count_glob off)"
check "with globstar the two-level APK IS found" "1" "$(count_glob on)"

# a one-level layout (existing hub consumers) must keep matching with globstar on
ONE="$FIX/tracker2/app/build/outputs/apk/release"
mkdir -p "$ONE"
: >"$ONE/app-release.apk"
one=$( shopt -s nullglob globstar; a=("$FIX/tracker2"/$GLOB); printf '%d' "${#a[@]}" )
check "globstar does not break single-level consumers" "1" "$one"

# the engine must actually enable it at the expansion site — every copy
for ENGINE in "${ENGINES[@]}"; do
NAME="$(basename "$ENGINE")"
if [[ -f "$ENGINE" ]]; then
  has=$(python3 -c "
import re,sys
t=open(sys.argv[1],encoding='utf8').read()
print('yes' if re.search(r'shopt -s [^\n]*globstar', t) else 'no')" "$ENGINE")
  check "[$NAME] engine enables globstar before expanding apk_glob" "yes" "$has"
fi
done

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[[ $FAIL -eq 0 ]]
