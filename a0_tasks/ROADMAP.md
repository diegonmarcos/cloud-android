# cloud-android ROADMAP

> See [`TASK_PLAN-parallel-space-v1.md`](./TASK_PLAN-parallel-space-v1.md) for
> the active first task. This file tracks milestones + future tasks.

## Milestones

| # | Milestone | Status | Target |
|---|-----------|--------|--------|
| M0 | Repo scaffold (framework from cloud/, 8 solution stubs, CI) | ✅ DONE | 2026-04-22 |
| M1 | FS redirector proof — hooked `open()` rewrites one path (v1 Phase 1) | Planned | 2026-04-29 |
| M2 | Pine + SQLiteDatabase capture (v1 Phase 2) | Planned | 2026-05-06 |
| M3 | System-service stubs + demo guest launch (v1 Phase 3) | Planned | 2026-05-13 |
| M4 | First real guest app running with data export (v1 Phase 4) | Planned | 2026-05-20 |
| M5 | Hardening + anti-detection (v1 Phase 5) | Planned | 2026-05-27 |
| M6 | Shizuku fallback shipped (parallel track) | Planned | 2026-05-13 |

## Backlog (future tasks — not yet started)

- **v2: multi-instance** — run two copies of WhatsApp side-by-side.
- **v2: device-ID spoofing** — per-guest `ANDROID_ID`, `IMEI`, `wifi_mac`.
- **v2: GMS emulation** — microG-style Google Play Services shim inside the workspace.
- **v2: remote controller** — optional Mattermost/MCP bridge so the workspace can be driven headlessly.
- **v2: Play Integrity bypass research** — purely research track; document the state of the art.
- **ops: Waydroid NixOS module** — add to `b_infra/` so Waydroid devices are reproducible from config.
- **ops: termux integration** — bridge `unix/bb_flakes_termux` so workspace APKs can be built from a Termux shell.

## Done

- M0: scaffold — see git log 2026-04-22.
