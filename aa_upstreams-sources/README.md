# ea_upstreams-sources/

**The canonical home of every third-party upstream working copy** we fork or
cherry-pick from (owner decision 2026-06-12 — clones live IN these subdirs, no
longer as top-level `ea_<role>-<upstream>/` siblings).

Everything inside the subdirectories is **gitignored workspace content** (the
`ea_*-*/` rule covers this folder): full upstream clones, never vendored, fully
reproducible. Only this README is tracked — it is the inventory that lets any
fresh `unix/` clone re-create the workspace deterministically.

## Three consumers, one home

1. **`ea_cloud-comms/` forks (constellation)** — `./build.sh materialize-fork
   <key>` clones the upstream at `build.json::forks.<key>.pinned_tag` into the
   subdir named by `forks.<key>.tracker_dir` and applies the committed patch
   series from `ea_cloud-comms/forks/<key>/patches/`. Same input → same tree.
2. **`ea_cloud-superapp/libs/` cherry-picks** — `build.sh sync-net` etc. copy
   include-lists from these clones into in-tree gradle modules.
3. **`ea_cloud-ide/` forks (wrapper)** — same materialize-fork contract as
   comms (`ea_cloud-ide/build.sh materialize-fork <key>`, registry of record =
   `ea_cloud-ide/build.json::forks.<key>.tracker_dir`). The wrapper bundles the
   PINNED UPSTREAM RELEASE APKs (`bundle-forks`, sha256-verified) until the
   patched forks build from these trackers.

| Subdir | Upstream | Consumer | How to (re)create |
|---|---|---|---|
| `mail-fairmail/` | M66B/FairEmail (GPL-3.0) | comms fork `mail` (pinned+patched) + superapp libs:mail | `ea_cloud-comms/build.sh materialize-fork mail` |
| `chat-mattermost/` | mattermost/mattermost-mobile (Apache-2.0) | comms fork `chat` | `… materialize-fork chat` (after push decision) |
| `chat-element/` | element-hq/element-x-android (AGPL-3.0) | comms fork `matrix` | `… materialize-fork matrix` (blocked on Matrix stack) |
| `dialer-fossify/` | FossifyOrg/Phone (GPL-3.0) | comms fork `dialer` | `… materialize-fork dialer` |
| `editor-acode/` | Acode-Foundation/Acode (MIT) @ v1.12.4 | ide fork `editor` | `ea_cloud-ide/build.sh materialize-fork editor` |
| `files-amaze/` | TeamAmaze/AmazeFileManager (GPL-3.0) @ v3.11.2 | ide fork `files` | `… materialize-fork files` |
| `files-amaze-utils/` | TeamAmaze/AmazeFileUtilities (GPL-3.0) @ v1.94 | ide fork `utils` (not yet cloned) | `… materialize-fork utils` |
| `cal-davx5/` | bitfireAT/davx5-ose (GPL-3.0) | superapp libs:cal | `git clone` per ea_cloud-superapp/build.json::upstreams |
| `feed-feeder/` | spacecowboy/Feeder (GPL-3.0) | superapp libs:feed | idem |
| `net-wireguard/` | WireGuard/wireguard-android (Apache-2.0) | superapp libs:net (`sync-net`) | idem |
| `vault-keepassdx/` | Kunzisoft/KeePassDX (GPL-3.0) | superapp libs:vault | idem |

## Why no submodules?

These upstreams are large (~300 MiB+ combined). Tracking them — even as
submodules — adds friction for every casual clone. Pinned tags + committed
patch series (comms) and include-list sync commands (superapp) give the same
reproducibility without the weight.
