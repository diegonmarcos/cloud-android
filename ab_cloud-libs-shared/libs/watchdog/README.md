# libs:watchdog

The ssh bridge `ac_cloud-watchdog` uses, shared **by reference** (a module
`dir` in the consuming app's `build.json::modules`) so it is one
implementation rather than a copy per app. It also ships its own APK, because
`ab_cloud-libs` scans this directory and a directory with a `build.gradle`
under a scan root is a module.

| file | what |
|---|---|
| `WatchdogSsh.kt` | JSch over loopback. Key generation, backend **fallback**, `screen()` one-shot, `open()` → a live `Panel` |
| `WatchdogBridge.kt` | the `AndroidWatchdog` JavascriptInterface: `start`/`key`/`tick`/`resize`/`stop`, `setBackend`, `publicKey` |

**Fallback, not a preference:** the preferred env is tried first and the others
after it, because which Linux is on a phone is not knowable from here and
either may be the only one present. The error reports the *preferred* env's
failure — `termux: connection refused` on a nix-on-droid phone points at the
wrong thing.

**No pty.** Two shapes, both `ChannelExec`: one question/one answer for the
static report, and a long-lived `tui --serve` for the session. A pty would mean
finding the end of an answer by watching for a prompt or a pause, which paints
half-drawn screens whenever the far side is slow. A sentinel line the
transcript cannot contain says exactly where a screen ends.

See `ac_cloud-watchdog/README.md` and `cloud-u-linux/da_watchdog/ARCHITECTURE.md`.
