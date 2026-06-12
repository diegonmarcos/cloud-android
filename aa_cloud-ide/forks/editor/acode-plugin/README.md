# Acode JS plugin (original code)

The JS-side integration for the editor fork, shipped as a **regular Acode
plugin** so it rides upstream Acode updates without patch churn. Bundled into the
`ea_upstreams-sources/editor-acode/` fork at build time (NOT a `git am` patch).

Responsibilities:

- Report opened files / workspaces to the native `EditorExportProvider`
  (`workspaces`, `recent_files`, `git_repos` tables from
  `../../../contract/ide-ipc-v1.json`).
- Register the shared workspace root (`../../../data/ide-endpoints.json::workspace.root_path`).
- Add a "back to Cloud-IDE" switcher entry that deep-links the hub.
- Pre-fill SFTP presets + the gitea remote base from `ide-endpoints.json`
  (SFTP presets are WireGuard-only).

Scaffold only — the plugin manifest (`plugin.json`) + entry point land when the
editor fork is built (Phase 2c). See `../README.md` for the native↔JS split.
