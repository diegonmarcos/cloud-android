# Fork: editor (Acode)

- **Upstream**: https://github.com/Acode-Foundation/Acode.git (MIT, Cordova hybrid — JS 62% / Java 14% / TS 12%)
- **App id**: `com.diegonmarcos.ide.editor`
- **Tracker**: `ea_upstreams-sources/editor-acode/`
- **Pinned tag**: `v1.12.4` (latest at scaffold, 2026-06-11 — upstream releases ~weekly)
- **Priority**: 3 — build LAST, once the patch pipeline is proven (fastest-moving upstream).
- **Owns tables**: `workspaces`, `recent_files`, `git_repos`.

## Fork strategy — minimize the native diff

Acode is a Cordova app with a stable JS plugin API. Keep the **native** patch
series tiny and put behavior in a bundled JS plugin so it rides upstream updates
for free:

- **Native patches** (`patches/`): applicationId/branding → `com.diegonmarcos.ide.editor`,
  plus ONE Cordova plugin (Java) that declares `EditorExportProvider` +
  `IIdeService` stub via `<config-file target="AndroidManifest.xml">` in its
  `plugin.xml`. (Pattern PRE-VERIFIED viable — `cordova-ContentProviderPlugin`
  precedent; confirm at the pinned tag.)
- **JS plugin** (`acode-plugin/`, original code — NOT a patch): recent-file
  reporting, workspace registration, hub switcher entry, gitea/SFTP presets from
  `../../data/ide-endpoints.json`. Bundled into the fork at build time.

## Status

Scaffold only. Next steps for the dev agent:

1. `./build.sh materialize-fork editor` (tag already pinned).
2. Phase-0 spike already concluded the Cordova provider hosting is viable —
   confirm against `v1.12.4`, then author the native plugin + JS plugin.
3. SFTP: built-in Acode SFTP gets per-VM presets from `ide-endpoints.json`
   (WG-only — surface the flag in the preset name).
4. Git: evaluate the existing Acode git/SSH plugin ecosystem (e.g.
   `acode.ssh.client`) before writing our own — document the choice here.

## Tester

- CI: clean clone at pin + `git am` of the native series applies with zero fuzz.
- Instrumented: opening a file in Acode appears in hub `recent_files` within 5s;
  `IIdeService.openFile()` opens the right buffer.
- e2e: an SFTP preset connects over WG.
