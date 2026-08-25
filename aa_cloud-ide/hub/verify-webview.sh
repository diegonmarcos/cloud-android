#!/usr/bin/env bash
# Static coherence check for the hub's WebView-launcher transform (v1, no
# Android SDK / frontend bundle required — full assembleDebug is CI's job).
set -uo pipefail
cd "$(dirname "$0")"
fail=0

echo "[1/4] no references to archived classes in src/main/java"
if grep -rEn 'IdeProvider|IdeService|IdeContract|ForkLauncher|ForkRegistry|NavBar|NavOverlayService|BundledForkInstaller' src/main/java; then
    echo "FAIL: archived-class reference found"; fail=1
else
    echo "OK: none found"
fi

echo "[2/4] MainActivity.kt loads the frontend + enables domStorage"
if grep -q 'loadUrl("file:///android_asset/frontend/index.html")' src/main/java/com/diegonmarcos/ide/MainActivity.kt \
   && grep -q 'domStorageEnabled = true' src/main/java/com/diegonmarcos/ide/MainActivity.kt; then
    echo "OK"
else
    echo "FAIL: MainActivity.kt missing loadUrl or domStorageEnabled"; fail=1
fi

echo "[3/4] manifest: IPC/queries gone, networkSecurityConfig + INTERNET present"
manifest=src/main/AndroidManifest.xml
if grep -qE 'IdeProvider|IdeService|<queries>' "$manifest"; then
    echo "FAIL: manifest still references IdeProvider/IdeService/<queries>"; fail=1
elif ! grep -q 'networkSecurityConfig="@xml/network_security_config"' "$manifest"; then
    echo "FAIL: manifest missing networkSecurityConfig"; fail=1
elif ! grep -q 'android.permission.INTERNET' "$manifest"; then
    echo "FAIL: manifest missing INTERNET permission"; fail=1
else
    echo "OK"
fi

echo "[4/4] network_security_config.xml exists and scopes cleartext to 127.0.0.1"
nsc=src/main/res/xml/network_security_config.xml
if [ -f "$nsc" ] && grep -q 'cleartextTrafficPermitted="true"' "$nsc" && grep -q '127.0.0.1' "$nsc"; then
    echo "OK"
else
    echo "FAIL: $nsc missing or not scoped to 127.0.0.1"; fail=1
fi

if [ "$fail" -eq 0 ]; then
    echo "=== ALL CHECKS PASSED ==="
else
    echo "=== CHECKS FAILED ==="
fi
exit "$fail"
