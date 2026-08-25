#!/usr/bin/env bash
# Tester: zoomies pets extracted from app/ into a proper libs/launcher-zoomies module.
#
# The animated status-bar pet (PetStrengthView + its vendored zoomies GIFs) used
# to live inside app/ (and a stray empty ea_zoomies-pets/ app folder existed at
# the unix root). zoomies is ONE library, not an app folder — this asserts the
# extraction to ea_cloud-superapp/libs/launcher-zoomies is wired the same way as the other
# 25 lib modules: data-driven module entry in build.json + app dependency + the
# moved source repackaged + the consumer importing the new package.
#
# Static wiring tester (no device / no gradle run): asserts the exact markers.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"        # → ea_cloud-superapp
UNIX="$(cd "$ROOT/.." && pwd)"                    # → ~/git/cloud-unix
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has()   { grep -qF "$2" "$ROOT/$1" 2>/dev/null && ok "$3" || bad "$3 ($1)"; }
hasnt() { grep -qF "$2" "$ROOT/$1" 2>/dev/null && bad "$3 ($1)" || ok "$3"; }
exists()   { [ -e "$ROOT/$1" ] && ok "$2" || bad "$2 ($1 missing)"; }
absent()   { [ -e "$1" ] && bad "$2 ($1 still exists)" || ok "$2"; }

LIB="libs/launcher-zoomies"
PET="$LIB/src/main/java/com/diegonmarcos/superapp/zoomies/PetStrengthView.kt"

echo "== T1: libs/launcher-zoomies module files exist =="
exists "$LIB/build.gradle"                 "module build.gradle"
exists "$LIB/consumer-rules.pro"           "consumer-rules.pro"
exists "$LIB/src/main/AndroidManifest.xml" "AndroidManifest.xml"
exists "$PET"                              "PetStrengthView.kt in zoomies package"

echo "== T2: PetStrengthView repackaged to com.diegonmarcos.superapp.zoomies =="
has   "$PET" "package com.diegonmarcos.superapp.zoomies" "PetStrengthView declares the zoomies package"
absent "$ROOT/app/src/main/java/com/diegonmarcos/superapp/launcher/PetStrengthView.kt" \
       "old app-module PetStrengthView.kt is gone"

echo "== T3: assets moved into the lib, none left in app =="
[ -d "$ROOT/$LIB/src/main/assets/zoomies" ] && ok "assets/zoomies/ lives in the lib" \
  || bad "assets/zoomies/ not under $LIB"
absent "$ROOT/app/src/main/assets/zoomies" "app/ no longer bundles zoomies assets"
# runtime path unchanged: PetStrengthView still reads "zoomies/<animal>" from assets.
has "$PET" 'zoomies/$animal' "asset path 'zoomies/<animal>' unchanged (lib assets merge to same path)"

echo "== T4: module build.gradle is a minimal android library (appcompat, no config) =="
has "$LIB/build.gradle" "com.android.library"                         "android library plugin"
has "$LIB/build.gradle" "namespace 'com.diegonmarcos.superapp.zoomies'" "namespace set"
has "$LIB/build.gradle" "androidx.appcompat:appcompat"                "declares appcompat (AppCompatImageView)"

echo "== T5: data-driven wiring — build.json module + app dependency =="
# settings.gradle iterates build.json::modules, so the module MUST be declared there.
python3 - "$ROOT/build.json" <<'PY' && ok "build.json declares libs:launcher-zoomies + app depends on it" || bad "build.json wiring missing"
import json,sys
d=json.load(open(sys.argv[1]))
m=d.get("modules",{})
assert "libs:launcher-zoomies" in m, "no libs:launcher-zoomies module entry"
assert "libs:launcher-zoomies" in m["app"]["depends_on"], "app does not depend on libs:launcher-zoomies"
PY
has "app/build.gradle" "project(':libs:launcher-zoomies')" "app/build.gradle implements :libs:launcher-zoomies"

echo "== T6: consumer imports the new package =="
STRIP="app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherStatusStripView.kt"
has "$STRIP" "import com.diegonmarcos.superapp.zoomies.PetStrengthView" "LauncherStatusStripView imports moved class"

echo "== T7: stray ea_zoomies-pets app folder removed =="
absent "$UNIX/ea_zoomies-pets" "ea_zoomies-pets/ root app folder is gone"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
