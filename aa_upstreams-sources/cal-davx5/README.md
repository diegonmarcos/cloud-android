# cal-davx5 — DAVx5 (CalDAV / CardDAV client)

| Field | Value |
|---|---|
| Upstream | https://github.com/bitfireAT/davx5-ose.git |
| License | GPL-3.0-or-later |
| Local clone path | `../../ea_cal-davx5/` |
| Last pinned commit | `c80ba52` (2026-05-29) |
| Cherry-pick target | `ea_cloud-superapp/libs/cal/` |
| Status | Empty stub — module wired in `settings.gradle` + `libs/cal/build.gradle`, no code yet |

## Re-clone

```bash
git clone https://github.com/bitfireAT/davx5-ose.git \
  ~/git/unix/ea_cal-davx5
git -C ~/git/unix/ea_cal-davx5 checkout c80ba52
```

## What we'll cherry-pick

The DAV sync engine + iCalendar parsing layer — `at.bitfire.davdroid.*`
core packages. UI surfaces will be re-implemented in the superapp's
Fragment style; we only mirror the protocol-level code.
