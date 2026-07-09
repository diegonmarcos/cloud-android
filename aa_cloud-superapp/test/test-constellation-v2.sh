#!/usr/bin/env bash
# Tester for Constellation AppStore v2 fixes (this session's feedback batch).
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
UPD="$APP/libs/updater/src/main/java/com/diegonmarcos/superapp/updater"
CFG="$APP/app/src/main/java/com/diegonmarcos/superapp/configs"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -qF "$2" "$1" 2>/dev/null && ok "$3" || bad "$3"; }

echo "== T1: real runtime enable toggle gates the workers =="
has "$UPD/AutoUpdatePrefs.kt" "fun enabled(ctx: Context)" "AutoUpdatePrefs.enabled exists"
has "$UPD/UpdateWorker.kt" "AutoUpdatePrefs.enabled(applicationContext)" "self-update worker gates on enabled"
has "$UPD/Updater.kt" "AutoUpdatePrefs.enabled(context)" "Updater.start gates on enabled"
has "$CFG/ConstellationWorker.kt" "AutoUpdatePrefs.enabled" "fleet worker gates on enabled"

echo "== T2: fleet manifest carries the GH release URL per app =="
jq -e '.apps[] | select(.id=="superapp") | .release_url and .asset' "$APP/data/constellation-fleet.json" >/dev/null 2>&1 \
  && ok "manifest has release_url + asset" || bad "manifest missing release_url/asset"
has "$UPD/Fleet.kt" "val releaseUrl: String" "Fleet.App carries releaseUrl"
has "$CFG/ConstellationFragment.kt" "app.releaseUrl" "UI shows the release URL link"

echo "== T3: per-app CONCURRENT status (fixes Dialer no-status / Chat loop) =="
has "$CFG/ConstellationFragment.kt" "thread(name = \"fleet-check-" "each app checked on its own thread"
grep -q 'for (app in apps)' "$CFG/ConstellationFragment.kt" && grep -q 'thread(name = "fleet-check' "$CFG/ConstellationFragment.kt" \
  && ok "checkAll spawns a thread per app (non-blocking)" || bad "checkAll not concurrent"
has "$CFG/ConstellationWorker.kt" "Result.success()" "fleet worker returns success (no retry loop)"

echo "== T4: Update All + Cancel =="
has "$UPD/Fleet.kt" "fun installAll(" "Fleet.installAll (Update All)"
has "$CFG/ConstellationFragment.kt" "Update all" "UI has Update-all button"
has "$UPD/UpdateProgress.kt" "object Cancelled" "UpdateProgress has Cancelled state"
has "$UPD/Updater.kt" "fun cancelNow(" "Updater.cancelNow exists"
has "$APP/app/src/main/java/com/diegonmarcos/superapp/updater/UpdateOverlayFragment.kt" "Updater.cancelNow" "overlay Cancel button wired"
grep -q '"action:update_all"' "$APP/build.json" && ok "Config Update tile → Update All" || bad "Config Update tile not repointed"

echo "== T5: update notification deep-links to the Constellation page (not Home) =="
has "$CFG/ConstellationWorker.kt" '"shortcut_action", "action:constellation"' "notification uses shortcut_action deep-link"
grep -q '"open_action"' "$CFG/ConstellationWorker.kt" && bad "old open_action extra still present" || ok "old dead open_action extra removed"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
