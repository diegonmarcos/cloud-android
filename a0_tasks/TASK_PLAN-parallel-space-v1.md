# TASK_PLAN — Parallel Space / Virtual Engine v1

**Opened**: 2026-04-22
**Owner**: Diego
**Repo**: `cloud-u-android`
**Status**: SCOPING — architecture locked, no code yet

> First real task of this repo. Goal: a working launcher-host APK that runs
> a single guest app inside a virtual engine and exposes its SQLite
> databases and `files/` directory at `/sdcard/Workspace/data/<pkg>/` — with
> **no root** and the guest app's own APK signature untouched.

---

## Mission

You paid for the device. Android's sandbox keeps your own data hostage inside
`/data/data/<pkg>/`. Patching the app is the 2020-era answer and loses to
Play Integrity in 2026. The modern answer is a **parallel space**: host the
guest app inside an APK you control, so every `open()` / `SQLiteDatabase`
call the guest makes is intercepted and redirected to a path you own.

---

## Architecture (locked)

Three pillars. Each is its own solution in `a_solutions/`; all bind together
at `ab-ui_workspace-launcher`.

### Pillar 1 — Hooking engine (Java/Kotlin)

**Solution**: `aa-eng_hooking-core`
**Tech**: [Pine](https://github.com/canyie/pine) primary, [SandHook](https://github.com/ganyao114/SandHook) fallback, `java.lang.reflect.Proxy` for system-service stubs.

Intercepts before Android framework sees the call:

- `ActivityManager` — rewrite `startActivity` / `bindService` / `registerReceiver` intents so they target the host's stub activity pool.
- `PackageManager` — return the guest's real `PackageInfo` / `ApplicationInfo` / `ComponentInfo` so the guest's own feature gates stay happy.
- `SQLiteDatabase.openDatabase` + `SQLiteOpenHelper` — hook the path argument before the framework normalises it. Logs the redirect for observability.
- `Context.getFilesDir()` / `getDatabasePath()` / `getExternalFilesDir()` — return workspace paths, not `/data/data/host`.

### Pillar 2 — Virtual system server (Java dynamic proxies)

**Solution**: `aa-eng_system-server-stubs`

Proxies for:

| Service | Why we fake it |
|---------|----------------|
| `IActivityManager` | Launch guest activities without the host declaring them all |
| `IPackageManager` | Lie about which apps are installed (sandbox view) |
| `INotificationManager` | Route guest notifications under the host channel / UID |
| `Settings.Secure` / `Settings.System` | Provide per-guest `ANDROID_ID`, fake `IMEI`, optionally fake location |
| `LocationManager` | Per-guest location overrides (future) |

All proxies created via `Proxy.newProxyInstance` against the real binder's
interface; only the handful of methods we care about are overridden, rest
delegate to the real binder with `IBinder.transact`.

### Pillar 3 — FS redirector (C++ / Dobby)

**Solution**: `aa-eng_fs-redirect-native`

Inline-hooks `libc` via [Dobby](https://github.com/jmpews/Dobby). Symbols:

```
open / openat / openat2 / creat
fopen / fopen64 / freopen
stat / fstatat / lstat / statx
access / faccessat / faccessat2
mkdir / mkdirat
unlink / unlinkat
rename / renameat / renameat2
opendir / scandir / readdir*
link / symlink / readlink
chmod / chown / utimensat
```

Rewrite rule (per-guest, driven by `build.json`):

```
/data/data/<guest-pkg>/**   ⇒   /sdcard/Workspace/data/<guest-pkg>/**
/data/user/<uid>/<guest>/** ⇒   /sdcard/Workspace/data/<guest-pkg>/**
```

Implementation notes:

- Rewrite happens in the host's process only; no privileged component.
- Original path is kept in the redirect map so the host can expose a "real vs
  virtual" view in the UI.
- Uses TLS-stored guarded_reentry flag so recursion during rewrite is safe.

### Supporting pieces

- **Manifest stub pool** (`ac-lib_manifest-stubs`) — 100 `<activity android:name=".StubActivity{N}">` in the host manifest + runtime classloader swap. When guest wants `com.foo.BarActivity`, map → `StubActivityN`, load `BarActivity`'s bytecode via `PathClassLoader` chained to the guest APK on disk.
- **SQLCipher wrapper** (`ad-sec_sqlcipher-wrapper`) — optional transparent encryption layer so extracted DBs don't leak the guest's own key rotation. Keys in Android Keystore hardware-backed slot.
- **Data exporter** (`ae-tool_data-exporter`) — UI that walks `/sdcard/Workspace/data/<guest>/databases/*` and does a plain `cp` → `/sdcard/Export/<guest>/<timestamp>/`. Optional SQLCipher decrypt.
- **Shizuku fallback** (`ae-tool_shizuku-client`) — parallel product, not part of the engine. For devices where the user prefers ADB-granted privileges over hooking. Ship it alongside so "give me my data" always has a path.

---

## Phased plan

Each phase is shippable in isolation and has an explicit test.

### Phase 0 — scaffold (DONE with repo init)

- [x] Declarative framework copied from `cloud/`.
- [x] 8 solution stubs with `build.json`.
- [x] `build.sh` dispatcher + engine.
- [x] GHA `ship.yml` builds APK artifacts.
- **Test**: `./build.sh config && ./build.sh workflow` runs clean.

### Phase 1 — FS redirector proof (Week 1)

Smallest useful slice: a command-line APK that opens one hard-coded file
through a hooked `open()` and proves the path was rewritten.

- [ ] `aa-eng_fs-redirect-native`: Dobby integration, CMake wired to NDK r27.
- [ ] Hook `open` / `openat` only.
- [ ] Minimal host APK (inside `ab-ui_workspace-launcher` dev variant) that:
  1. Calls `fopen("/data/data/com.demo.guest/databases/test.db", "w")`.
  2. Hook rewrites to `/sdcard/Workspace/data/com.demo.guest/databases/test.db`.
  3. App reports both paths; exit 0.
- **Test**: instrumented test under Waydroid (`./build.sh adb surface-waydroid am instrument …`) asserts file appears at `/sdcard/Workspace/...` and NOT at `/data/data/...`.

### Phase 2 — Pine hook + SQLiteDatabase capture (Week 2)

- [ ] `aa-eng_hooking-core`: Pine AAR dependency wired.
- [ ] Hook `SQLiteDatabase.openDatabase(String, …)` — log path, rewrite, call super with new path.
- [ ] Demo activity opens a SQLite DB; assert DB file lives at workspace path.
- **Test**: instrumented test opens DB, writes row, closes, verifies `/sdcard/Workspace/.../test.db` exists and contains the row.

### Phase 3 — System-service stubs (Week 3)

- [ ] `aa-eng_system-server-stubs`: proxy for `IActivityManager.startActivity`.
- [ ] Stub activity pool (`ac-lib_manifest-stubs`, 10 stubs for v1).
- [ ] Launch a second-party demo APK (`com.diegonmarcos.guest-demo`) from inside the host; it should render, handle lifecycle, write to DB — all redirected.
- **Test**: smoke test under Waydroid; guest-demo main activity visible on screen, DB written to workspace path.

### Phase 4 — First real guest app (Week 4)

Pick a **low-stakes** target first — something without Play Integrity:

- [ ] Load target guest APK from `/sdcard/Workspace/apks/<pkg>.apk`.
- [ ] Parse its manifest, map main activity → stub, launch.
- [ ] Let it run for 60s; verify it wrote *something* to `/sdcard/Workspace/data/<pkg>/`.
- **Test**: run guest-demo app end-to-end, export its DB with `ae-tool_data-exporter`, open the exported `.db` in sqlite3 CLI, verify schema + at least one row.

### Phase 5 — Hardening (Week 5+)

- [ ] Anti-detection:
  - rewrite `/proc/self/maps` reads to hide Dobby trampolines
  - strip `FLAG_SECURE` leaks
  - randomise hook sites (no fixed offsets)
- [ ] Per-guest UID simulation (fake `Process.myUid()`).
- [ ] `ad-sec_sqlcipher-wrapper` for sensitive guests.

### Phase 6 — Shizuku fallback (parallel track, any time)

- [ ] `ae-tool_shizuku-client`: standalone APK that uses Shizuku to `cp /data/data/<pkg>/databases/* /sdcard/Export/`.
- [ ] Works on any guest that allows that level of access via Shizuku (most non-hardened apps).
- **Test**: install on pixel-7a with Shizuku running; extract DB from pre-chosen guest.

---

## Out of scope for v1

- Multi-instance (running two copies of the same app). Ship as v2.
- Device-ID spoofing for anti-fingerprinting. Ship as v2.
- GMS emulation (guest asks for Google Play Services). Most hardened apps need this and it is a separate 2-week task on its own.
- Widevine / DRM guests (Netflix, etc.) — the DRM handshake will refuse to run inside any parallel space. Explicitly not a target.

---

## Risks

| Risk | Mitigation |
|------|------------|
| Pine/Dobby break on Android 16 beta | Pin to Android 15 (SDK 35) for v1; treat 16 as v2 work. |
| Play Integrity on guest detects hooks | v1 targets apps that do not use Play Integrity. Hardened-app support is v2. |
| Waydroid diverges from real hardware | Always mirror phase tests on pixel-7a before calling a phase done. |
| GPL contamination | Hooking-core + fs-redirect link GPL libs (Pine is Apache 2.0, Dobby is Apache 2.0 — both OK). SandHook fallback is GPL v3; keep it optional-only. |

---

## Success criteria for v1

1. Host APK installs on pixel-7a (Android 15) **without root**.
2. Pre-chosen guest demo APK runs inside it, without crashing, for ≥ 60s.
3. Guest's SQLite database lands in `/sdcard/Workspace/data/<pkg>/databases/` — verified with `adb shell ls` from the host process.
4. `./build.sh ship data-exporter` runs on the device and produces a valid `.db` file at `/sdcard/Export/<pkg>/<timestamp>.db`.
5. Same flow works under `surface-waydroid` for CI.
6. Pre-commit hook blocks the keystore; only `*.keystore.enc` is ever committed.
7. All five acceptance tests above run from `./build.sh health` (or a successor `./build.sh test`).
