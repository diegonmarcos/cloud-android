# cloud-watchdog

The my-watchdog panel, on a phone. **This app draws no dashboard.**

It ssh's to nix-on-droid on `127.0.0.1` (Termux as fallback), runs
`my-watchdog-tui tui --serve`, sends key names down its stdin and paints the
frames that come back.

```
  WebView  ──key:p──►  WatchdogBridge ──► WatchdogSsh ──ssh 127.0.0.1:8022──►
                                                        my-watchdog-tui --serve
  WebView  ◄──frame──  __wdEvent      ◄────────────────  <pre class="tui">…
```

## Why it works this way

Every command the CLI has, and the CLI's exact screen — neither achievable by
writing them here:

* the **keys** are `Monitor::on_key` in the watchdog repo, the real dispatch
  table, including whatever was added to it last week;
* the **screen** is the ratatui buffer, the real layout, frames and colours.

A re-implemented layout diverged on all three for a week. A re-implemented key
table would diverge the first time a key was added to the panel and not to the
copy. So the app runs the panel and gets out of the way.

**The key bar is labels over a pipe, not behaviour.** A button sends a name;
the panel decides what it does. The bar can be wrong about which keys deserve
a thumb, never about what one does. 38 of 49 keys have a button; the rest are
aliases, digit-reachable sub-tabs, or the quit keys, which are deliberately
not forwarded.

## Why ssh and not an Intent

nix-on-droid is another app with its own uid. `RUN_COMMAND` fires and forgets
and the output is on the far side of a sandbox. `127.0.0.1` is shared between
app sandboxes, so its sshd is reachable with no cross-UID barrier and no
packet leaving the phone. **cloud-ide already does exactly this** — same JSch
fork, same self-generated ECDSA key, same manual `authorized_keys` step.

## Setup

1. Install the APK.
2. Run sshd in nix-on-droid (`:8022`) or Termux (`:8023`).
3. Put the app's public key in that env's `~/.ssh/authorized_keys` — the app
   prints it on its error screen.
4. `my-watchdog-tui` must be on that env's PATH.

## Cross references

| | |
|---|---|
| the panel + daemon | `cloud-u-linux/da_watchdog` — see its `ARCHITECTURE.md` |
| the ssh bridge | `ab_cloud-libs-shared/libs/watchdog` (shared by reference, also ships its own APK) |
| the same mechanism | `ac_cloud-ide` — `SshBackend`, `TerminalBridge`, `data/terminal-targets.json` |
| host/port/user/command | `build.json::watchdog` → baked to `BuildConfig`, never Kotlin constants |
| fleet + console actions | `cloud-infra/1_cloud-configs/dist/watchdog.json` |

## Known gaps

* `versionCode` is 1 and never bumped, so the in-app updater is inert —
  reinstall by hand.
* The transcript is a live session, but the **static** HTML report
  (`~/.watchdog/html/index-mobile.html`) is frozen at export time.
