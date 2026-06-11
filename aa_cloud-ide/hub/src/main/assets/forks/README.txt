Embedded app APKs — the SELF-CONTAINED Cloud-IDE wrapper bundle.

This directory is populated AT BUILD TIME by `./build.sh bundle-forks` (called
automatically by `build` / `release`): it downloads each fork's PINNED upstream
release APK (build.json::forks.<key>.embedded.url) and verifies the PINNED
sha256 (build fails on mismatch), so the single Cloud-IDE APK physically
carries Acode (editor.apk) + Amaze File Manager (files.apk) inside it.

On first tap the hub installs the bundled APK via PackageInstaller
(BundledForkInstaller) and launches it under the Cloud-IDE overlay nav bar.

The .apk files are gitignored (binaries, never committed) — this README keeps
the directory present so AssetManager.list("forks") works on a clean clone.

Data-driven from build.json::forks.<key>.embedded {url, sha256, package}.
When our patched forks publish to GHCR, the source swaps like cloud-comms'.
