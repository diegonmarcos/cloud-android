# Fork: utils (Amaze File Utilities)

- **Upstream**: https://github.com/TeamAmaze/AmazeFileUtilities.git (GPL-3.0, Kotlin)
- **App id**: `com.diegonmarcos.ide.utils`
- **Tracker**: `ea_files-amaze-utils/`
- **Pinned tag**: `v1.94` (latest, 2026-06-11 — upstream near-dormant since 2024-07)
- **Priority**: 2 — build SECOND (smallest diff, but must follow `files` for the
  symmetric pairing rename).
- **Owns tables**: `storage_summary`.

## Status

Scaffold only. Next steps for the dev agent:

1. `./build.sh materialize-fork utils` (tag already pinned).
2. Patches: branding (applicationId → `com.diegonmarcos.ide.utils`); **pairing
   rename** — re-namespace the FM-targeted intent key
   `com.amaze.fileutilities.AFM_LOCATE_FILE_NAME` →
   `com.diegonmarcos.ide.utils.AFM_LOCATE_FILE_NAME`, matching the files fork's
   `MainActivity` reader; exporter.
3. Exporter: `UtilsExportProvider` under `com.diegonmarcos.ide.utils.provider`
   implementing `storage_summary` from `../../contract/ide-ipc-v1.json`.
4. Default scan target from `../../data/ide-endpoints.json::utils`.

## Honest note

Upstream is effectively dormant (v1.94, July 2024) — drift cost ≈ zero, but
expect no upstream fixes either. Treat this as the most "owned" of the three
forks. It already ships AIDL, so the exporter has a native precedent to follow.

## Tester

- CI: clean clone at pin + `git am` applies with zero fuzz.
- Instrumented: `storage_summary` parity vs the app's own UI numbers after a scan.
- Pairing: FM "Analyse" entry launches this fork (proves the symmetric rename).
