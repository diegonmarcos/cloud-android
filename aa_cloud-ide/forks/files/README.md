# Fork: files (Amaze File Manager)

- **Upstream**: https://github.com/TeamAmaze/AmazeFileManager.git (GPL-3.0, Kotlin+Java)
- **App id**: `com.diegonmarcos.ide.files`
- **Tracker**: `ea_files-amaze/`
- **Pinned tag**: `v3.11.2` (verified latest stable, 2026-06-11)
- **Priority**: 1 — build this fork FIRST (native, core of the file domain).
- **Owns tables**: `recent_files`, `transfers`.

## Status

Scaffold only. Next steps for the dev agent:

1. `./build.sh materialize-fork files` (tag already pinned).
2. Author the four patches (branding / provisioning / exporter / switcher — see
   `../README.md`).
3. **Pairing rename**: `AboutActivity.PACKAGE_AMAZE_UTILS` →
   `com.diegonmarcos.ide.utils`; keep the `AFM_LOCATE_FILE_NAME` intent-extra
   key in sync with the utils fork's re-namespaced key.
4. Default bookmarks from `../../data/ide-endpoints.json`: the workspace root +
   the SFTP presets (WG-only — surface the flag in the bookmark label).
5. Exporter: `FilesExportProvider` under `com.diegonmarcos.ide.files.provider`
   implementing `recent_files` + `transfers` from `../../contract/ide-ipc-v1.json`.
6. Permissions: uses `MANAGE_EXTERNAL_STORAGE` (self-distributed via GHCR, no
   Play-policy constraint) to share the workspace tree.

## Tester

- CI: clean clone at pin + `git am` of the series applies with zero fuzz.
- Instrumented: `recent_files` provider rows match files opened in a scripted
  session.
- e2e: an SFTP bookmark connects to `10.0.0.6:22` with WG up; fails cleanly with
  WG down.
- Pairing: the FM "Analyse" action launches the `com.diegonmarcos.ide.utils` fork.
