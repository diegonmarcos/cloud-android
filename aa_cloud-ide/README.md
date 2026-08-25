# Cloud-IDE

A **constellation** of three minimally-forked upstream developer apps plus a
thin original **hub** APK. The hub switches between the forks and brokers their
data to [Cloud-SuperApp](../ea_cloud-superapp/) over signature-gated IPC, so
SuperApp can show a dev surface (recent files, workspaces, git status, storage)
while the *editor / file-manager engines* live in the forks. Twin of
[Cloud-Comms](../ea_cloud-comms/) — same engine, hub, and IPC pattern.

```
ea_cloud-ide/
├── build.sh            ← universal dispatcher (build/verify-contract/materialize-fork/…)
├── build.json          ← data-driven: module graph + toolchain + FORK REGISTRY + IPC
├── flake.nix           ← Nix devShell: JDK 17 + Gradle + AGP + Android SDK + Node/Cordova (editor fork)
├── settings.gradle     ← mirrors build.json::modules (hub only)
├── contract/
│   ├── ide-ipc-v1.json         ← THE IPC contract (tables + AIDL) — source of truth
│   └── ide-ipc-v1.schema.json  ← JSON Schema; `./build.sh verify-contract`
├── data/
│   └── ide-endpoints.json      ← workspace root + gitea + SFTP presets (WG-only) + code-server (no secrets)
├── hub/                ← the hub APK (original code): switcher + IdeProvider + IdeService
└── forks/
    ├── files/   (Amaze File Manager, GPL-3.0, native)        — patches + docs
    ├── utils/   (Amaze File Utilities, GPL-3.0, native)      — patches + docs
    └── editor/  (Acode, MIT, Cordova)  — patches + acode-plugin/ (JS) + docs
```

## Architecture

Unlike Cloud-Comms there is **no server-side sync engine** — the shared resource
is a hub-owned on-device workspace tree (`/storage/emulated/0/CloudIDE/`) plus
existing dev endpoints (gitea over public :443, SFTP to the VMs over WireGuard,
code-server as a browser deep-link). Five APKs, **all signed with one key** (the
IPC permission is `protectionLevel="signature"` — the SAME constellation key as
Cloud-Comms + SuperApp):

| APK | id | role |
|-----|-----|------|
| hub | `com.diegonmarcos.ide` | switcher + unified `IdeProvider` (reads) + `IIdeService` (AIDL actions/push) |
| files fork | `com.diegonmarcos.ide.files` | Amaze FM + exporter → hub |
| utils fork | `com.diegonmarcos.ide.utils` | Amaze File Utilities + exporter → hub |
| editor fork | `com.diegonmarcos.ide.editor` | Acode + exporter → hub |
| SuperApp | `com.diegonmarcos.superapp` | consumes the hub surface (dev card hides if hub absent) |

Forks own their local DBs; the hub never copies data — it proxies queries to the
per-fork exporter providers and UNIONs them. Only file URIs + metadata cross the
boundary; content is reached via `openFile` / `revealInFiles` deep links.

## Build

```bash
./build.sh shell             # enter Nix devShell
./build.sh verify-contract   # validate the IPC contract (Phase-0 gate)
./build.sh build             # hub debug APK → dist/cloud-ide-hub-debug.apk
./build.sh materialize-fork files   # clone Amaze FM@v3.11.2 → tracker + apply patches
./build.sh build-fork files         # build the materialized fork
```

## Status — Phase 0 scaffold

- **Done**: project scaffold, IPC contract + schema + validator, hub APK
  (switcher UI + code-server tile, signature-gated `IdeProvider` + `IIdeService`,
  data-driven fork registry + endpoints), declarative fork structure with pinned
  tags (files `v3.11.2`, utils `v1.94`, editor `v1.12.4`), instrumented tests.
- **Next**: materialize + patch the **files** fork first (native, core), then
  **utils** (pairing rename), then **editor** (Acode, minimal native diff + JS
  plugin).
- **SuperApp wiring** (`libs:ide-client` dev card) is Phase 3 — additive, no
  fallback engine (hub absent → card hides).
- **Hub sharing**: Cloud-Comms scaffolded first; extract the shared broker +
  updater into a common module once both hubs build (see `forks/README.md`).

Full plan + decisions: [`../a0_tasks/PLAN_cloud-ide-constellation.md`](../a0_tasks/PLAN_cloud-ide-constellation.md).

## License

Each fork keeps its upstream license (GPL-3.0 / GPL-3.0 / MIT); they are separate
APKs, so no license mixing. The hub is original code.
