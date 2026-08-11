# cloud-android

Android **Parallel Space / Virtual Engine** workspace — data-ownership tooling
built on the same declarative framework as [`cloud/`](https://github.com/diegonmarcos/cloud).

> **Mission**: "You paid for the device. The OS treats you as a guest in your
> own apps. This repo is a sub-OS that sits between real Android and a guest
> app, so **your data stays yours** — no root required."

---

## Why this repo exists

Modern Android hides every app's `/data/data/<pkg>/` behind the same isolation
wall that keeps apps from spying on each other. That wall also blocks **you**
from backing up, exporting, or archiving your own WhatsApp history, notes,
chat databases, and so on. Two answers exist in 2026:

1. **Patch the APK** — works on simple apps, loses to Play Integrity on any
   hardened app (banking, streaming, messaging with E2EE attestation).
2. **Host the app inside a virtual engine** — the host process owns the
   syscalls, so every file the guest writes lives under a path **you**
   control. Works against hardened apps because the guest's *own* signature
   stays intact; it never notices it's being watched.

This repo is option 2, with option 1 (Shizuku-based extraction) kept as a
lightweight fallback.

---

## Framework (same as `cloud/`)

```
cloud-android/
├── build.sh                     # Root dispatcher — delegates to 1_configs/src/gha/scripts/*
├── config.json                  # SINGLE SOURCE OF TRUTH — devices, arch pillars, deps
├── .gitignore / .sops.yaml      # Secret-hygiene reinforced by pre-commit hook
│
├── 0_specs/                      # Specs (prod / eng / user)
├── 0_tasks/                     # ROADMAP + TASK_PLAN-*.md (work queue)
│
├── 1_configs/                 # UNIVERSAL BUILD ENGINE
│   ├── build.sh -> src/scripts/cloud-android-ship-repo-workflow-engine.sh
│   ├── src/
│   │   ├── scripts/             # cloud-android-<system>-<tech>-<action>-*.sh
│   │   ├── cicd/                # GHA workflow YAMLs (→ .github/workflows/)
│   │   ├── actions/             # GHA composite actions (→ .github/actions/)
│   │   ├── hooks/               # Git hooks (→ 1_configs/dist/hooks, active via .gitconfig)
│   │   └── modules/             # .gitmodules template
│   └── dist/                    # GENERATED — never edited by hand
│
├── 2_configs/                   # Generated per-solution configs
├── 3_secrets/                   # Sops-encrypted secrets (age key via vault)
├── 4_reports/                   # Security / audit reports
│
├── a_solutions/                 # SOLUTIONS — each is a self-contained build.json + src/
│   ├── aa-eng_hooking-core/             # Pillar 1 — Java hooking engine (Pine/SandHook)
│   ├── aa-eng_system-server-stubs/      # Pillar 2 — Virtual system server proxies
│   ├── aa-eng_fs-redirect-native/       # Pillar 3 — C++ libc hooks (Dobby)
│   ├── ac-lib_manifest-stubs/           # Stub Activity/Service pool + classloader swap
│   ├── ab-ui_workspace-launcher/        # Host APK (Jetpack Compose launcher)
│   ├── ad-sec_sqlcipher-wrapper/        # Transparent SQLCipher layer for guest DBs
│   ├── ae-tool_data-exporter/           # Direct-export UI (cp .db → /sdcard/Export)
│   └── ae-tool_shizuku-client/          # No-root fallback (ADB-granted privileges)
│
├── b_infra/                     # Build infra — Gradle/NDK pinning, Waydroid configs
└── c_devices/                   # Per-device overrides
```

**Category prefixes** (declared in `config.json`):

| Prefix | Layer |
|--------|-------|
| `aa-eng_` | Engine (core parallel-space) |
| `ab-ui_`  | UI / launcher |
| `ac-lib_` | Shared libraries |
| `ad-sec_` | Security / crypto |
| `ae-tool_` | Auxiliary tools (exporter, Shizuku) |
| `ba-bld_` | Build tooling |
| `bb-tst_` | Test / CI |

---

## Architecture — the three pillars

Every component answers "who is in the middle?":

| Pillar | Solution | Layer | What it intercepts |
|--------|----------|-------|-------------------|
| **Hooking engine** | `aa-eng_hooking-core` | Java/Kotlin (Pine / SandHook) | `IActivityManager`, `IPackageManager`, `SQLiteDatabase.openDatabase`, lifecycle callbacks |
| **Virtual system server** | `aa-eng_system-server-stubs` | Java dynamic proxies | The system's own service stubs — makes guest believe it's on real Android |
| **FS redirector** | `aa-eng_fs-redirect-native` | C++ (Dobby inline hooks) | `libc` `open`, `openat`, `fopen`, `stat`, `unlink`, `rename`, `opendir` |

Supporting pieces:

- **Manifest stubs** (`ac-lib_manifest-stubs`) — pre-declared `StubActivity{1..N}` in the host manifest so we can launch arbitrary guest components without knowing their names at install time. Runtime classloader swap loads the real guest class.
- **SQLCipher wrapper** (`ad-sec_sqlcipher-wrapper`) — re-encrypts guest databases with a workspace-held key so extracted data is useful even after the guest rotates its own secrets.
- **Data exporter** (`ae-tool_data-exporter`) — UI on top of the redirected FS. Single click = copy every `.db` / `.json` / proto out of the workspace to `/sdcard/Export/`.
- **Shizuku client** (`ae-tool_shizuku-client`) — lightweight path. No virtual engine, just ADB-granted read of `/data/data/<pkg>/`.

---

## The build system

Same contract as `cloud/`:

```sh
./build.sh ship <solution>     # build + sign + adb install
./build.sh build <solution>    # build only → dist/
./build.sh adb <device>        # adb shell into a declared device
./build.sh config              # regenerate 2_configs/dist from sources
./build.sh workflow            # rebuild src → dist → .github/ + .gitmodules + hooks
./build.sh secrets <solution>  # sops show/edit/encrypt/decrypt
./build.sh health              # L1-L3 probes across devices in config.json
./build.sh clean               # wipe dist/ + gradle + .cxx
./build.sh deps                # install system + android deps from config.json
```

Per-solution `build.sh` is a **symlink** to
`1_configs/src/gha/scripts/cloud-android-ship-container-engine.sh`, which reads
`build.json` and dispatches gradle / nix / adb-install. **Never edit the
engine symlink target per-solution** — extend `build.json` instead.

---

## Rules (same FIRE rules as `cloud/`)

1. **No inline commands with args.** Always fix the engine — never bypass it with a one-liner.
2. **Declarative only.** New behaviour → new field in `build.json` / `config.json`.
3. **Data-driven.** No hardcoded device IDs, package names, or paths outside JSON.
4. **A task is not done until it has a tester.** Every solution ships with integration tests before it's called complete.

---

## Getting started

```sh
git clone --recurse-submodules git@github.com:diegonmarcos/cloud-android.git
cd cloud-android
./build.sh deps        # check / install system deps
./build.sh workflow    # materialise hooks, .github/, .gitmodules from src
./build.sh config      # generate 2_configs/dist from build.json files
./build.sh build hooking-core
```

Current state: **scaffold only**. The first real implementation task lives in
[`0_tasks/TASK_PLAN-parallel-space-v1.md`](0_tasks/TASK_PLAN-parallel-space-v1.md).

---

## References

Study these in order before contributing to the engine pillars:

1. [VirtualApp (asLody)](https://github.com/asLody/VirtualApp) — historical bible of Android virtualisation.
2. [BlackBox](https://github.com/FBlackBox/BlackBox) — modern (Android 12–15) successor, clean code.
3. [Pine](https://github.com/canyie/pine) — the Java hooking library we depend on.
4. [Dobby](https://github.com/jmpews/Dobby) — the native inline-hook library.
5. [Shizuku](https://shizuku.rikka.app/) — for the non-virtual data-extraction path.

---

## License

TBD — engine components (hooking-core, fs-redirect-native) inherit copyleft
from their upstream libraries; the launcher and tools are MIT unless stated
otherwise in the solution's own `build.json`.
