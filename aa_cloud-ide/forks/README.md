# Cloud-IDE forks

Each fork is **declarative**: a pinned upstream tag + a committed patch series.
It is *never* a long-lived divergent clone.

The canonical registry — upstream repo, tracker dir, pinned tag, app id, blocked
state — lives in **`../build.json::forks`** (single source of truth; do not
duplicate it here). The fork **key is the IPC domain** (`editor`/`files`/`utils`),
so `forkUri = com.diegonmarcos.ide.<key>.provider`. Each `forks/<key>/` directory
holds only:

- `patches/NNNN-*.patch` — the entire fork diff as a `git am`-applicable series,
  in lexical order. Empty = a pure upstream checkout (valid during scaffolding).
- `SYNC_LOG.md` — one line per upstream bump recording the cost of re-applying.
- (`editor` only) `acode-plugin/` — the JS-side Acode plugin (original code, NOT
  a patch) bundled into the editor fork at build time.

## Lifecycle

```bash
# 1. Tag already pinned in build.json::forks.<key>.pinned_tag (verified 2026-06-11):
./build.sh materialize-fork files     # clone@tag → ea_files-amaze/ + apply patches
# 2. Hack in the materialized tracker clone, then export the diff back as patches:
git -C ../ea_files-amaze format-patch <upstream-tag> -o forks/files/patches
# 3. Build:
./build.sh build-fork files
```

## The four patch concerns (keep them as SEPARATE numbered patches)

Minimal + orthogonal patches survive upstream churn. Every fork's series should
factor into at most these four:

1. **branding** — applicationId → `com.diegonmarcos.ide.<domain>`, app name/icon.
2. **provisioning** — defaults from `data/ide-endpoints.json` (workspace root,
   SFTP presets, gitea/code-server URLs).
3. **exporter** — the `<domain>` ContentProvider + `IIdeService` stub that
   implements the tables it owns from `contract/ide-ipc-v1.json` under
   `com.diegonmarcos.ide.<domain>.provider`.
4. **switcher** — a "back to Cloud-IDE" entry that deep-links the hub.

## Table ownership (who exports what)

| domain | tracker dir | upstream | owns tables |
|--------|-------------|----------|-------------|
| files | `ea_files-amaze/` | AmazeFileManager v3.11.2 (GPL-3.0, native) | `recent_files`, `transfers` |
| utils | `ea_files-amaze-utils/` | AmazeFileUtilities v1.94 (GPL-3.0, native) | `storage_summary` |
| editor | `ea_editor-acode/` | Acode v1.12.4 (MIT, Cordova) | `workspaces`, `recent_files`, `git_repos` |

`recent_files` is exported by both editor and files; the hub UNIONs them (every
row carries `domain` so SuperApp can tell them apart). All tracker dirs match the
`ea_*-*/` gitignore rule and are never committed — only the patch series under
`forks/` is version-controlled.

## Pairing rename (files ⇄ utils) — PRE-LOCATED

Amaze FM and Utilities cross-reference each other's package by string. Both must
be renamed symmetrically (verified against upstream `release/4.0`, 2026-06-11):

- **files fork** (`AmazeFileManager`): `AboutActivity.PACKAGE_AMAZE_UTILS =
  "com.amaze.fileutilities"` → `com.diegonmarcos.ide.utils`; and it reads the
  intent extra `com.amaze.fileutilities.AFM_LOCATE_FILE_NAME` in `MainActivity`.
- **utils fork** (`AmazeFileUtilities`): re-namespace the FM-targeted intent key
  `com.amaze.fileutilities.AFM_LOCATE_FILE_NAME` → `com.diegonmarcos.ide.utils.…`
  to match the files fork's reader.

Re-grep both forks at their pins for any additional references before finalizing.

## Hub sharing with Cloud-Comms

`cloud-comms` was scaffolded first; its hub (switcher + broker + updater) is
structurally identical to this one. Once both build, extract the broker + updater
into a shared gradle module (consumed by both hubs) instead of maintaining two
copies. Until then this hub deliberately mirrors `ea_cloud-comms/hub`.
