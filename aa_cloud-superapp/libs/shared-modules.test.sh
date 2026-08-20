#!/usr/bin/env bash
# Guards the shared-module invariant across the whole constellation.
#
# Library modules used to be copy-pasted per app and they drifted: 6 apps sat
# 228 source lines behind superapp's updater, and browser existed as three
# byte-identical trees that nothing kept in step. Now a module is shared by
# giving it a `dir` in the consuming app's build.json::modules, and
# settings.gradle points that gradle path at the one canonical directory.
#
# Nothing here is hardcoded — the module list, the apps and the expected
# directories are all read from the build.json files, so adding a shared module
# needs no edit to this tester.
#
# Run from anywhere:  bash ea_cloud-superapp/libs/shared-modules.test.sh
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 1

fail=0
note() { printf '%-6s %s\n' "$1" "$2"; [ "$1" = FAIL ] && fail=1; return 0; }

# 1..3. Every `dir` resolves, points at a real module, and the consuming app
#       does NOT also carry a local copy at the default path.
while IFS='|' read -r app mod dir; do
    [ -z "$app" ] && continue
    local_path="$app/${mod//://}"
    target=$(cd "$app" 2>/dev/null && cd "$dir" 2>/dev/null && pwd -P)
    if [ -z "$target" ]; then
        note FAIL "$app/build.json: $mod dir=$dir does not exist"
    elif [ ! -f "$target/build.gradle" ]; then
        note FAIL "$app $mod -> $dir has no build.gradle — not a gradle module"
    else
        note ok "$app $mod -> $dir"
    fi
    [ -e "$local_path" ] && note FAIL "$local_path is a local copy shadowing the shared $dir — delete it"
done < <(python3 - <<'PY'
import json,glob
for bj in sorted(glob.glob('ea_cloud-*/build.json')):
    app=bj.split('/')[0]
    for k,v in json.load(open(bj)).get('modules',{}).items():
        if k.startswith('libs:') and isinstance(v,dict) and v.get('dir'):
            print(f"{app}|{k}|{v['dir']}")
PY
)

# 4. settings.gradle must actually honour `dir`, or every entry above is inert.
while read -r sg; do
    command grep -q '_spec?.dir' "$sg" \
        && note ok "$sg honours dir" \
        || note FAIL "$sg ignores build.json::modules.dir"
done < <(command ls -1 ea_cloud-*/settings.gradle)

# 5. No two apps may hold their own copy of the same module name — that is the
#    exact shape the drift came in. One name, one directory, everywhere.
while read -r line; do
    note FAIL "duplicated module tree: $line"
done < <(python3 - <<'PY'
import os,collections
seen=collections.defaultdict(list)
for app in sorted(d for d in os.listdir('.') if d.startswith('ea_cloud-')):
    L=os.path.join(app,'libs')
    if os.path.isdir(L):
        for m in sorted(os.listdir(L)):
            if os.path.isdir(os.path.join(L,m)): seen[m].append(os.path.join(L,m))
for m,ps in seen.items():
    if len(ps)>1: print(f"{m} lives in {len(ps)} places: {', '.join(ps)}")
PY
)
note ok "no module name owns more than one directory"

# 6. A shared module must read the CONSUMING app's build.json. A module-relative
#    path resolves to superapp's, so cloud-browser would take superapp's GHCR
#    image (updater) or superapp's log stream (devtools).
while read -r bg; do
    # Only actual parses count — a mention of build.json in a comment is fine.
    command grep -q 'parse(file(' "$bg" || continue
    # A module MAY additionally fall back to its own repo for a key the consumer
    # does not define (libs/voice does), but the FIRST read must be the app's.
    command grep -q 'parse(file("${rootDir}/build.json"))' "$bg" \
        && note ok "$bg reads \${rootDir}/build.json" \
        || note FAIL "$bg parses build.json by a module-relative path — must be \${rootDir}/build.json"
done < <(python3 - <<'PY'
import json,glob,os
dirs={os.path.normpath(os.path.join(bj.split('/')[0],v['dir']))
      for bj in glob.glob('ea_cloud-*/build.json')
      for k,v in json.load(open(bj)).get('modules',{}).items()
      if k.startswith('libs:') and isinstance(v,dict) and v.get('dir')}
for d in sorted(dirs):
    p=os.path.join(d,'build.gradle')
    if os.path.exists(p): print(p)
PY
)

# 7. The constellation permission is declared once, in the shared core, so it
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

# 8. The UI constant and the manifest must name the same permission string.
UI=ea_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/configs/ConstellationFragment.kt
command grep -q "\"$PERM\"" "$UI" \
    && note ok "Constellation UI uses the same permission string" \
    || note FAIL "$UI: CONSTELLATION_PERM does not match the manifest ($PERM)"

# 9. A declared module must have source. An empty one is build cost and a dead
#    feature slot pretending to be a feature.
while read -r line; do note FAIL "$line"; done < <(python3 - <<'PY'
import json,glob,os
for bj in sorted(glob.glob('ea_cloud-*/build.json')):
    app=bj.split('/')[0]
    for k,v in json.load(open(bj)).get('modules',{}).items():
        if not k.startswith('libs:'): continue
        d=v.get('dir') if isinstance(v,dict) else None
        p=os.path.normpath(os.path.join(app,d)) if d else os.path.join(app,k.replace(':','/'))
        if not os.path.isdir(p): continue
        n=sum(sum(1 for _ in open(os.path.join(dp,f),'rb'))
              for dp,dns,fs in os.walk(p) if '/build' not in dp
              for f in fs if f.endswith(('.kt','.java')))
        if n==0: print(f"{app} declares {k} but {p} has no source — drop the declaration or fill it")
PY
)
note ok "every declared module has source"

# 10. Every project(':libs:x') dependency must name a module the build declares.
#     Dropping a dead module from build.json leaves the dependency line behind,
#     and gradle only says "Project with path ':libs:x' could not be found".
while read -r line; do note FAIL "$line"; done < <(python3 - <<'PY'
import json,glob,re,os
for bj in sorted(glob.glob('ea_cloud-*/build.json')):
    app=bj.split('/')[0]
    declared=set(json.load(open(bj)).get('modules',{}))
    for bg in glob.glob(f'{app}/*/build.gradle'):
        for m in re.findall(r"project\(':([\w:-]+)'\)", open(bg).read()):
            if m not in declared:
                print(f"{bg} depends on :{m}, which {bj} does not declare")
PY
)
note ok "every project() dependency names a declared module"

# 11. ea_cloud-libs ships one APK per library module. Three places derive that
#     set from build.json::lib_apks — settings.gradle (the gradle modules),
#     build.sh (the asset names) and data/regen.sh (the Libs tab). They must
#     agree, or the store lists an APK the build never produced and the install
#     button 404s on the release asset.
LIBS_BJ=ea_cloud-libs/build.json
if [ -f "$LIBS_BJ" ]; then
    # LC_ALL=C: python sorts by byte, GNU sort by locale, and they disagree on
    # '-' vs '.' (Cloud-Lib-Voice-Vosk.apk vs Cloud-Lib-Voice.apk).
    shipped=$(bash ea_cloud-libs/build.sh list 2>/dev/null | command awk '{print $3}' | LC_ALL=C sort)
    fleeted=$(python3 - <<'PY'
import json
d=json.load(open('ea_cloud-superapp/data/constellation-fleet.json'))
a=d['apps'] if isinstance(d,dict) else d
print('\n'.join(sorted(x['asset'] for x in a
                       if x.get('kind')=='lib' and x.get('id','').startswith('lib-'))))
PY
)
    if [ "$shipped" = "$fleeted" ]; then
        note ok "ea_cloud-libs: $(printf '%s\n' "$shipped" | command grep -c . ) lib APKs, build and fleet agree"
    else
        note FAIL "ea_cloud-libs: build.sh and constellation-fleet.json disagree — rerun ea_cloud-superapp/data/regen.sh"
        diff <(printf '%s\n' "$shipped") <(printf '%s\n' "$fleeted") | command head -10
    fi
    # An excluded module must say why. A bare exclusion is indistinguishable from
    # a module someone silently dropped because it would not build.
    while read -r line; do note FAIL "$line"; done < <(python3 - <<'PY'
import json
for k,v in json.load(open('ea_cloud-libs/build.json'))['lib_apks'].get('exclude',{}).items():
    if not isinstance(v,str) or len(v) < 20:
        print(f"ea_cloud-libs excludes {k} without a reason — say why it cannot ship")
PY
)
fi

echo
[ "$fail" -eq 0 ] && echo "PASS — shared modules wired, no duplicated trees." \
                  || echo "FAIL — see above."
exit "$fail"
