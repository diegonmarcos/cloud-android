#!/usr/bin/env bash
# Tester: the launcher's ex-Cloud-Comms tiles point at the STANDALONE apps, not
# the decommissioned Cloud-Comms hub.
#
# cloud-comms (hub com.diegonmarcos.comms) is archived; its 4 forks are now
# standalone constellation apps (ea_cloud-{dialer,chat,mail,matrix}). The
# Communications tiles + the ui.external_apps registry + the FloatingNav comms
# parent used to route through `extapp:cloud-comms/<fork>` → the dead hub. This
# asserts every one of those now targets the standalone apps and that NO dead-hub
# reference (extapp:cloud-comms / cloud-comms external_apps id / install_app:
# cloud-comms / bare app:com.diegonmarcos.comms) survives.
#
# Static, data-driven (build.json is the single source of truth); also cross-
# checks each retargeted external_app resolves to an app in the constellation
# fleet, so tiles can never point at a package the AppStore can't install.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → ea_cloud-superapp
BJ="$APP/build.json"
FLEET="$APP/data/constellation-fleet.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
absent() { rg -q "$1" "$BJ" 2>/dev/null && bad "$2" || ok "$2"; }

echo "== T1: no dead Cloud-Comms hub references survive in build.json =="
absent 'extapp:cloud-comms'          "no extapp:cloud-comms/<fork> tile targets"
absent '"id": "cloud-comms"'         "no cloud-comms external_apps entry"
absent '"install_app": "cloud-comms"' "no FloatingNav install_app: cloud-comms"
absent '"app:com.diegonmarcos.comms"' "no bare app:<hub-pkg> launch target"
absent 'Cloud-Comms-Hub.apk'         "no Cloud-Comms-Hub.apk install URL"

echo "== T2: the 4 Communications tiles target the standalone apps =="
for t in cloud-mail cloud-chat cloud-matrix cloud-dialer; do
  rg -q "\"extapp:$t\"" "$BJ" 2>/dev/null && ok "tile target extapp:$t present" || bad "tile target extapp:$t missing"
done

echo "== T3: forkless external_apps entries exist for each standalone app =="
python3 - "$BJ" <<'PY' && ok "external_apps has cloud-{dialer,chat,mail,matrix}, forkless, package com.diegonmarcos.comms.<fork>" || bad "external_apps entries missing/malformed"
import json,sys
d=json.load(open(sys.argv[1]))
ext={e["id"]:e for e in d["ui"]["external_apps"]}
want={"cloud-dialer":"com.diegonmarcos.comms.dialer",
      "cloud-chat":"com.diegonmarcos.comms.chat",
      "cloud-mail":"com.diegonmarcos.comms.mail",
      "cloud-matrix":"com.diegonmarcos.comms.matrix"}
for i,pkg in want.items():
    e=ext[i]
    assert e["hub_package"]==pkg and e["install_package"]==pkg, (i,e)
    assert e.get("forks",{})=={}, (i,"forks not empty")
assert "cloud-comms" not in ext, "cloud-comms entry still present"
PY

echo "== T4: every retargeted external_app resolves to a real constellation-fleet app =="
python3 - "$BJ" "$FLEET" <<'PY' && ok "cloud-{dialer,chat,mail,matrix} packages all present in the fleet" || bad "a retargeted app is not in the fleet (AppStore could not install it)"
import json,sys
d=json.load(open(sys.argv[1])); fleet=json.load(open(sys.argv[2]))
ext={e["id"]:e for e in d["ui"]["external_apps"]}
fpkgs={a["package"] for a in fleet["apps"]}
for i in ("cloud-dialer","cloud-chat","cloud-mail","cloud-matrix"):
    assert ext[i]["hub_package"] in fpkgs, (i, ext[i]["hub_package"], "not in fleet")
PY

echo "== T5: FloatingNav comms parent opens the Communications section (not the dead hub) =="
python3 - "$BJ" <<'PY' && ok "FloatingNav 'Cloud Comms' parent → section:communication, no install_app" || bad "FloatingNav comms parent still points at the hub"
import json,sys
d=json.load(open(sys.argv[1]))
fn=d["ui"]["floating_nav"] if "floating_nav" in d.get("ui",{}) else d["floating_nav"]
p=[x for x in fn["parents"] if x["label"]=="Cloud Comms"][0]
assert p["target"]=="section:communication", p
assert "install_app" not in p, p
PY

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
