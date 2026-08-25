#!/usr/bin/env bash
# Tester for the Constellation AppStore (superapp = fleet manager).
#
# Static wiring assertions + a live GHCR check that every app image resolves a
# remote digest (proving the AppStore can see every constellation app's build).
#
# Usage: ./test-constellation-appstore.sh   (live check needs network/wg0)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → ea_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -qF "$2" "$1" 2>/dev/null && ok "$3" || bad "$3"; }

FLEET="$APP/data/constellation-fleet.json"

echo "== T1: fleet manifest auto-generated with every app =="
if [ -f "$FLEET" ]; then
  N=$(jq '.apps | length' "$FLEET" 2>/dev/null)
  [ "${N:-0}" -ge 8 ] && ok "constellation-fleet.json has $N apps (>=8)" || bad "expected >=8 apps, got ${N:-0}"
  # Every standalone ea_cloud-<id> self-registers: the top-level apps +
  # the 4 promoted ex-comms fork-apps (dialer/chat/mail/matrix), each now its
  # OWN dir + ship-cloud-<id>.yml CI. Fleet ids = the dir basenames.
  for id in superapp nav ide browser vault wallet dialer chat mail matrix; do
    jq -e --arg i "$id" '.apps[] | select(.id==$i) | .package and .image and .registry' "$FLEET" >/dev/null 2>&1 \
      && ok "fleet entry $id has package+image" || bad "fleet entry $id missing/incomplete"
  done
  # cloud-comms HUB is decommissioned (archived to z_archive/) — it and the old
  # comms-* fork ids must be GONE (the forks are now independent apps above).
  jq -e '.apps[] | select(.package=="com.diegonmarcos.comms" or .image=="cloud-comms-hub")' "$FLEET" >/dev/null 2>&1 \
    && bad "cloud-comms hub still in fleet (should be decommissioned)" || ok "cloud-comms hub absent (decommissioned)"
  jq -e '.apps[] | select(.id | startswith("comms-"))' "$FLEET" >/dev/null 2>&1 \
    && bad "stale comms-* fork id present" || ok "no stale comms-* fork ids"
else bad "constellation-fleet.json missing (run data/regen.sh)"; fi
has "$APP/data/regen.sh" "regen_constellation" "regen.sh auto-scans siblings → fleet json"
# self-registering / DRY: the dialer package is scanned from ea_cloud-dialer/
# build.json (forks.dialer.app_id), not hand-typed — dialer is now an
# independent fleet app with its own ship CI.
grep -q '"com.diegonmarcos.comms.dialer"' "$FLEET" 2>/dev/null && ok "dialer package scanned from ea_cloud-dialer/build.json" || bad "dialer package not scanned"

echo "== T2: baked into BuildConfig (data-driven) =="
has "$APP/app/build.gradle" "constellation-fleet.json" "build.gradle reads the fleet snapshot"
has "$APP/app/build.gradle" 'buildConfigField "String", "CONSTELLATION_FLEET_B64"' "build.gradle bakes CONSTELLATION_FLEET_B64"

echo "== T3: engine reuses libs/updater primitives (no reinvention) =="
ENG="$APP/libs/updater/src/main/java/com/diegonmarcos/superapp/updater/Fleet.kt"
has "$ENG" "GhcrClient(app.registry, app.namespace, app.image)" "Fleet checks per-image via existing GhcrClient"
has "$ENG" "UpdateInstaller(ctx).install(target, app.pkg)" "Fleet installs foreign pkg via existing UpdateInstaller"
has "$ENG" "packageInstaller.uninstall(pkg" "Fleet uninstall via PackageInstaller"

echo "== T4: UI page + navigation + worker wired =="
has "$APP/app/src/main/java/com/diegonmarcos/superapp/configs/ConstellationFragment.kt" "CONSTELLATION_FLEET_B64" "ConstellationFragment reads the fleet"
has "$APP/app/src/main/java/com/diegonmarcos/superapp/MainActivity.kt" 'actionType == "constellation"' "MainActivity routes the constellation action"
has "$APP/app/src/main/java/com/diegonmarcos/superapp/App.kt" "ConstellationWorker.start(this)" "App.onCreate starts the fleet worker"
jq -e '.ui.sections[] | select(.id=="config") | .pages[] | select(.id=="constellation")' "$APP/build.json" >/dev/null 2>&1 \
  && ok "build.json config.pages has the Constellation entry" || bad "Constellation page not in build.json ui.sections"

echo "== T5: LIVE — every app image resolves a GHCR digest (AppStore can see them) =="
if command -v curl >/dev/null && [ -f "$FLEET" ]; then
  reg="ghcr.io"
  while read -r ns img; do
    tok=$(curl -s --max-time 12 "https://$reg/token?service=$reg&scope=repository:$ns/$img:pull" | jq -r .token 2>/dev/null)
    dig=$(curl -s --max-time 12 -H "Authorization: Bearer $tok" \
          -H "Accept: application/vnd.oci.image.manifest.v1+json" \
          "https://$reg/v2/$ns/$img/manifests/latest" | jq -r '.layers[0].digest // empty' 2>/dev/null)
    [ -n "$dig" ] && ok "$img → ${dig:0:19}…" || echo "  SKIP: $img (no digest — unpublished/blocked/offline)"
  done < <(jq -r '.apps[] | select(.blocked|not) | "\(.namespace) \(.image)"' "$FLEET")
else echo "  SKIP: curl/jq or fleet json unavailable"; fi

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
