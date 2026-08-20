#!/usr/bin/env bash
# Guards the shared-module invariant for libs:core and libs:updater.
#
# These two modules used to be copy-pasted into every constellation app. They
# drifted: 6 apps sat 228 source lines behind superapp's updater, so fixes
# landed in one app and silently missed the rest. Now each app's build.json
# gives them a `dir` pointing at ea_cloud-superapp/libs/<name>, and
# settings.gradle honours it — one directory, no sync step, nothing to drift.
#
# Run from the repo root:  bash ea_cloud-superapp/libs/shared-modules.test.sh
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 1

SHARED=(core updater)
APPS=(nav browser vault wallet calendar news contacts)
fail=0
note() { printf '%-6s %s\n' "$1" "$2"; [ "$1" = FAIL ] && fail=1; return 0; }

# 1. The canonical copy exists.
for m in "${SHARED[@]}"; do
    [ -d "ea_cloud-superapp/libs/$m" ] \
        && note ok "canonical ea_cloud-superapp/libs/$m" \
        || note FAIL "canonical ea_cloud-superapp/libs/$m is MISSING"
done

# 2. No app carries a local copy — a reappearing directory means someone
#    re-vendored it and drift can start again.
for a in "${APPS[@]}"; do
    for m in "${SHARED[@]}"; do
        [ -e "ea_cloud-$a/libs/$m" ] \
            && note FAIL "ea_cloud-$a/libs/$m is a local copy — delete it, build.json::modules already points at superapp's" \
            || note ok "ea_cloud-$a/libs/$m not vendored"
    done
done

# 3. Every app declares `dir` for both, and it resolves to the canonical tree.
for a in "${APPS[@]}"; do
    for m in "${SHARED[@]}"; do
        dir=$(python3 -c "
import json,sys
spec=json.load(open('ea_cloud-$a/build.json'))['modules'].get('libs:$m')
print('' if spec is None else spec.get('dir',''))" 2>/dev/null)
        if [ -z "$dir" ]; then
            note FAIL "ea_cloud-$a/build.json: libs:$m has no \"dir\" — it would resolve to a local copy"
            continue
        fi
        real=$(cd "ea_cloud-$a" 2>/dev/null && cd "$dir" 2>/dev/null && pwd -P)
        want=$(cd "ea_cloud-superapp/libs/$m" && pwd -P)
        [ "$real" = "$want" ] \
            && note ok "ea_cloud-$a libs:$m -> $dir" \
            || note FAIL "ea_cloud-$a libs:$m dir=$dir resolves to '${real:-<nothing>}', expected $want"
    done
done

# 4. settings.gradle must actually honour `dir`, or every entry above is inert.
for a in "${APPS[@]}" superapp; do
    command grep -q '_spec?.dir' "ea_cloud-$a/settings.gradle" \
        && note ok "ea_cloud-$a/settings.gradle honours dir" \
        || note FAIL "ea_cloud-$a/settings.gradle ignores build.json::modules.dir"
done

# 5. The shared updater must read the CONSUMING app's build.json. A
#    module-relative path resolves to superapp's, which would hand every app
#    superapp's GHCR image — cloud-browser would update itself into superapp.
command grep -q 'parse(file("${rootDir}/build.json"))' ea_cloud-superapp/libs/updater/build.gradle \
    && note ok "libs/updater reads \${rootDir}/build.json" \
    || note FAIL "libs/updater/build.gradle must parse \${rootDir}/build.json, not a module-relative path"

# 6. The constellation permission is declared once, in the shared core, so it
#    merges into every app. That is what makes Cloud Perms on-by-default.
CORE_MANIFEST=ea_cloud-superapp/libs/core/src/main/AndroidManifest.xml
PERM=com.diegonmarcos.cloud.permission.CONSTELLATION_DATA
if command grep -q "$PERM" "$CORE_MANIFEST" 2>/dev/null; then
    command grep -q 'android:protectionLevel="signature"' "$CORE_MANIFEST" \
        && note ok "CONSTELLATION_DATA declared signature-level in libs:core" \
        || note FAIL "$CORE_MANIFEST: CONSTELLATION_DATA must be protectionLevel=\"signature\""
    command grep -q "<uses-permission android:name=\"$PERM\"" "$CORE_MANIFEST" \
        && note ok "CONSTELLATION_DATA also requested (uses-permission)" \
        || note FAIL "$CORE_MANIFEST: declaring the permission without <uses-permission> makes the app readable but unable to read"
else
    note FAIL "$CORE_MANIFEST does not declare $PERM"
fi

# 7. The UI constant and the manifest must name the same permission string.
UI=ea_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/configs/ConstellationFragment.kt
command grep -q "\"$PERM\"" "$UI" \
    && note ok "Constellation UI uses the same permission string" \
    || note FAIL "$UI: CONSTELLATION_PERM does not match the manifest ($PERM)"

echo
[ "$fail" -eq 0 ] && echo "PASS — shared modules wired, no vendored copies." \
                  || echo "FAIL — see above."
exit "$fail"
