# 9_others — cloud-u-android CI/CD & Build System

Single source of truth for **all** executable logic, GHA workflows, git hooks,
and repo configuration in `cloud-u-android/`. Mirrors the pattern used in
[`cloud/9_others/`](https://github.com/diegonmarcos/cloud-infra/tree/main/9_others).

Everything under `.github/`, `.gitmodules`, and `.gitconfig` at repo root is
**generated output** — never edited directly.

```
build.sh workflow   →   src/  →  dist/  →  .github/ + repo root
```

---

## Directory Structure

```
9_others/
├── build.sh -> src/scripts/cloud-android-ship-repo-workflow-engine.sh
├── README.md                   # This file
├── src/                        # SOURCE — edit here
│   ├── scripts/                # cloud-android-<system>-<tech>-<action>-*.sh
│   ├── cicd/                   # GHA workflow YAMLs (deployed → .github/workflows/)
│   ├── actions/                # GHA composite actions (deployed → .github/actions/)
│   ├── hooks/                  # Git hooks (deployed via gitconfig hooksPath)
│   ├── modules/                # .gitmodules template
│   └── gitconfig               # Repo git config (deployed → .gitconfig)
└── dist/                       # GENERATED — do not edit
    ├── scripts/                # → .github/workflows/scripts (symlink)
    ├── cicd/                   # → .github/workflows/*.yml
    ├── actions/                # → .github/actions/
    ├── hooks/                  # → via .gitconfig core.hooksPath
    └── gitconfig               # → .gitconfig
```

---

## Scripts naming convention

```
cloud-android-<system>-<technology>-<action>-<tool>.sh
```

### `cloud-android-ship-*` — build, deploy, CI/CD

| Script | Action | Description |
|--------|--------|-------------|
| `cloud-android-ship-lib.sh` | — | Shared library (logging, config reader, dep check) |
| `cloud-android-ship-container-engine.sh` | — | Per-solution engine (symlinked as build.sh by each solution) — wrapper over `cloud-android-build-apk-engine.sh` |
| `cloud-android-build-apk-engine.sh` | — | Reads `build.json`, dispatches gradle/nix/adb-install pipeline |
| `cloud-android-ship-repo-workflow-engine.sh` | — | `src/` → `dist/` → deploy generated artifacts |
| `cloud-android-ship-repo-workflow-gen.sh` | — | Alias for the workflow engine |
| `cloud-android-ship-repo-config-gen.sh` | — | Expand `config.json` + each `build.json` → `2_configs/dist/*.json` |
| `cloud-android-ship-repo-deps.sh` | — | Print / install system + android deps from `config.json` |
| `cloud-android-ship-repo-clean.sh` | — | Wipe all `dist/` + gradle + `.cxx` |
| `cloud-android-ship-repo-secrets.sh` | — | sops pass-through (show/edit/encrypt/decrypt) |
| `cloud-android-ship-repo-adb.sh` | — | `adb shell` into a declared device |
| `cloud-android-ship-repo-status.sh` | — | Installed-packages list on a device |
| `cloud-android-ship-orchestrate-ship.sh` | — | Top-level ship orchestrator |
| `cloud-android-ship-orchestrate-build.sh` | — | Build one or all solutions |
| `cloud-android-ship-orchestrate-device.sh` | — | Ship every solution targeting one device |

### `cloud-android-health-*` — probes

| Script | Description |
|--------|-------------|
| `cloud-android-health-full.sh` | L1-L3 device reach (adb / ssh) |

### `cloud-android-git-sync.sh` — safer pull/push

`git sync remote` or `git sync local` — auto-stash + rebase + submodule sync.

---

## CI/CD Workflows (`src/cicd/`)

| Workflow | Trigger | Description |
|----------|---------|-------------|
| `ship.yml` | Push to `a_solutions/*/src/` | Detect → build → sign → upload artifact |
| `health.yml` | Cron + manual | Framework sanity (config + workflow regen) |
| `sync-submodules.yml` | Push + cron | Auto-update cloud-data / tools submodules |

---

## Hooks (`src/hooks/`)

Deployed to `0_git/dist/hooks/`, active via `.gitconfig` `core.hooksPath`.

| Hook | Purpose |
|------|---------|
| `pre-commit` | Block any secret-shaped file (raw keystores, `.env`, raw private keys, un-encrypted `secrets.yaml`, force-staged gitignored files) |
| `pre-push` | Rebase submodules to upstream HEAD before push |
| `post-merge` | Auto-sync submodules after `git pull` |
| `post-checkout` | Re-init submodules after branch switch |

---

## Symlinks map

| From | To | Count |
|------|----|-------|
| `a_solutions/*/build.sh` | `../../1_cicd/src/scripts/cloud-android-ship-container-engine.sh` | 8 (and growing) |
| `9_others/build.sh` | `src/scripts/cloud-android-ship-repo-workflow-engine.sh` | 1 |
| `.github/workflows/scripts` | `../../1_cicd/dist/scripts` | 1 (post-build) |

---

## Adding a new solution

```sh
mkdir -p a_solutions/<category-prefix>_<name>/src
ln -sf ../../1_cicd/src/scripts/cloud-android-ship-container-engine.sh a_solutions/<category-prefix>_<name>/build.sh
cat > a_solutions/<category-prefix>_<name>/build.json <<'JSON'
{
  "name": "<name>",
  "description": "…",
  "category": "<eng|ui|lib|sec|tool|…>",
  "type":     "<apk|library|native>",
  "tech":     { "language": "kotlin" },
  "build":    { "gradle_task": ":app:assembleRelease" },
  "deploy":   { "device": "surface-waydroid", "method": "adb-install" }
}
JSON
./build.sh config            # regenerate 2_configs/dist/solutions.json
./build.sh build <name>      # dry-run the build
```
