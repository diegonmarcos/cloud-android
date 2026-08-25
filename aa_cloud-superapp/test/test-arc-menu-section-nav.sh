#!/usr/bin/env bash
# Tester: the Canopus arc-menu (and every openSectionPage caller) navigates an
# action-page INTO its own section instead of rendering it over Home.
#
# BUG: tapping Constellation in the bottom Canopus arc menu (shown only on the
# home section) rendered ConstellationFragment straight over the home screen —
# currentSection stayed "home", Back went Home, the page never landed in the
# Configs section. Root cause: LauncherNavController.openSectionPage dispatched a
# page's `action:` (constellation → action:constellation) and RETURNED EARLY,
# before the block that establishes the parent section as the back-stack base.
# Non-action pages hit that block first, so they navigated correctly — the
# asymmetry WAS the bug.
#
# FIX: establish the section base (goSection) BEFORE the action early-return, so
# action-pages get the same parent-section context as normal pages.
#
# Static ordering tester (no device): asserts goSection precedes the action
# dispatch inside openSectionPage.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"          # → ea_cloud-superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

NAV="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherNavController.kt"
CAN="$APP/app/src/main/java/com/diegonmarcos/superapp/launcher/CanopusStar.kt"

echo "== T1: the arc menu routes taps through the page: grammar (→ openSectionPage) =="
grep -qF 'page:$section/${it.id}' "$CAN" 2>/dev/null \
  && ok "CanopusStar emits page:<section>/<id> targets" \
  || bad "CanopusStar no longer emits page: targets (routing changed?)"

echo "== T2: openSectionPage establishes the section base BEFORE the action dispatch =="
# Line of the base-establishment (goSection) vs the action early-return.
base_ln=$(grep -n 'goSection(sectionId, Sections.byId(sectionId)' "$NAV" | head -1 | cut -d: -f1)
act_ln=$(grep -n 'host.dispatchTarget(pageAction)' "$NAV" | head -1 | cut -d: -f1)
if [ -n "$base_ln" ] && [ -n "$act_ln" ]; then
  [ "$base_ln" -lt "$act_ln" ] \
    && ok "goSection (line $base_ln) precedes action dispatch (line $act_ln)" \
    || bad "action dispatch (line $act_ln) still runs BEFORE goSection (line $base_ln) — regression"
else
  bad "could not locate goSection / dispatchTarget markers in openSectionPage"
fi

echo "== T3: the action dispatch + base guard both still exist (no accidental removal) =="
grep -qF 'if (pageAction.isNotBlank()) { host.dispatchTarget(pageAction); return }' "$NAV" 2>/dev/null \
  && ok "action-page dispatch preserved" || bad "action-page dispatch missing"
grep -qF 'if (host.currentSection != sectionId) {' "$NAV" 2>/dev/null \
  && ok "section-base guard preserved" || bad "section-base guard missing"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
