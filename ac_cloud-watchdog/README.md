# cloud-watchdog

The my-watchdog panel, on a phone. **This app invents no page and no number.**

The interface ships INSIDE the APK: `my-watchdog-tui app-shell` renders the
whole drawer, every panel frame and every table header against an empty
machine, and that page is baked in as `watchdog-app.html`. The app therefore
opens whether or not it can reach anything — the failure that made it show a
terminal error where a dashboard should be was the UI being a property of
having measured something.

The numbers arrive separately, and **the app does not fetch them**. It has no
ssh client, no key and no socket. nix-on-droid — which has the mesh keys, the
fleet declaration and an ssh client — runs `my-watchdog-tui android-bridge`,
measures whatever the app asks for, and pushes the envelope IN through a
ContentProvider the app exports for the env's uid (rewritten 2026-09-02, after
three builds failed inside the app's ssh client where nothing could see them).

```
  APK opens ──► watchdog-app.html                (UI, baked in, no machine in it)
  drawer tap ─► AndroidWatchdog.refresh(alias) ─► filesDir/bridge/wants = alias
  env loop  ──► content query …/wants           ─► my-watchdog-tui snapshot [alias]
  env loop  ──► content write …/snapshot/<alias> ─► BridgeProvider → filesDir/bridge/<alias>.json
  WebView   ◄── window.__wdRender(json)          ◄── on arrival, and on every open
```

## The pages

Thirty-one, and not one of them is written here. The drawer is generated from
the panel's own `TABS` table, and `da_watchdog/src/tui/monitor/data/pages.rs`
says which array backs each node:

| tab | pages |
|---|---|
| proc | normal · tree · zombies · parentless |
| containers | compose · images · containers · volumes · network |
| fleet | wg0-ipv4 · wg0-ipv6 · wg-public-ipv4 · wg-public-ipv6 · storage |
| firewall | consolidated · os · container |
| logs | summary · kernel · system · user · docker · network · ssh · watchdog |
| history | the per-day rollup |
| files | the tree the panel read |
| about | about · rules · update · app-map |

plus overview, report, machines and the raw envelope.

**The phone had seventeen of these and nothing said so.** A tab node with no
backing array rendered dimmed and inert, which looks exactly like a page whose
data has not arrived — so eleven pages that were only ever missing a producer
sat there through every release. They are derived at export time now, on the
machine that can see them, and `tests::every_leaf_is_backed` makes a missing
producer a red build.

Adding a page is therefore a change in the CLI repo only: put it in `TABS`,
give it a row in `BACKED_BY`, and it appears here on the next build.

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

## Why a provider and not ssh

nix-on-droid is another app with its own uid; `127.0.0.1` is shared, so its
sshd WAS reachable — and the app's JSch client failed three ways in a row
(a first-run key shadowing the vault key, ed25519 needing Bouncy Castle, and
one more nobody could read off a uid-scoped logcat). A provider inverts the
direction: the env, which already has everything, does the work, and every
step is a shell command you can run by hand over ssh:

```sh
A=content://com.diegonmarcos.watchdog.bridge
content query --uri $A/wants                       # what the user picked
content query --uri $A/snapshots                   # what has arrived, sizes, times
content query --uri $A/log                         # the APP's own log
content write --uri $A/snapshot/local < env.json   # push one by hand
```

(`content` is an app_process: from proot it needs the Android runtime
environment — `android-bridge` reads it off the proot launcher's environ.)
## Setup

1. Install the APK.
2. In nix-on-droid, run the env side (the app used to push the binary over
   ssh; now it is fetched from the release):
   ```sh
   mkdir -p ~/.cache/cloud-watchdog/bin && cd ~/.cache/cloud-watchdog/bin
   curl -fsSL -o my-watchdog-tui https://github.com/diegonmarcos/cloud-u-linux/releases/download/my-watchdog-latest/my-watchdog-tui-aarch64
   curl -fsSL -o my-watchdog     https://github.com/diegonmarcos/cloud-u-linux/releases/download/my-watchdog-latest/my-watchdog-aarch64
   chmod +x my-watchdog my-watchdog-tui
   (nohup ./my-watchdog --no-tray >/dev/null 2>&1 &)
   (nohup ./my-watchdog-tui android-bridge >> ~/.cache/cloud-watchdog/bridge.log 2>&1 &)
   ```
3. Open the app. The drawer lists the fleet as soon as the first local
   envelope lands (≤ 5 s); tap a machine to have the env measure it.
## Cross references

| | |
|---|---|
| the panel + daemon | `cloud-u-linux/da_watchdog` — see its `ARCHITECTURE.md` |
| the provider + page bridge | `ab_cloud-libs-shared/libs/watchdog` — `BridgeProvider`, `WatchdogBridge` |
| the env loop | `cloud-u-linux/da_watchdog/src/tui/android_bridge.rs` |
| the same mechanism | `ac_cloud-ide` — `SshBackend`, `TerminalBridge`, `data/terminal-targets.json` |
| host/port/user/command | `build.json::watchdog` → baked to `BuildConfig`, never Kotlin constants |
| fleet + console actions | `cloud-infra/1_cloud-configs/dist/watchdog.json` |

## If the app shows an empty interface

**Every page blank, on load, before you touch anything** was a crash in the
shell's own script: with no data yet it drew the legacy overview boxes against
the empty placeholder shell and called `.toFixed` on a `{}`, which threw and
took the rest of the script — the nav handlers included — down with it. The
overview shows a "waiting for a machine" state now until a machine answers, and
the number formatters coerce non-numbers to 0 so one bad field can never blank
the page again. (fixed 2026-09-02)

## If the drawer says "no fleet" or a machine never fills in

The app reaches nothing, so look at the env. From a shell in nix-on-droid:

```sh
tail ~/.cache/cloud-watchdog/bridge.log         # "pushed local (131219 B)" every 5 s?
pgrep -x my-watchdog-tui || echo "bridge loop not running"
pgrep -x my-watchdog     || echo "sampler not running"
content query --uri content://com.diegonmarcos.watchdog.bridge/log   # what the app saw
```

`android-bridge: wants: … SecurityException` means the provider refused the
env's uid — the package ids it trusts are `BridgeProvider.ENV_PACKAGES`.
`… snapshot/oci-apps: …` names an ssh problem on the env's hop to that peer,
the same one `my-watchdog-tui snapshot oci-apps` would show.
## Known gaps

* `versionCode` is 1 and never bumped, so the in-app updater is inert —
  reinstall by hand.
* The transcript is a live session, but the **static** HTML report
  (`~/.watchdog/html/index-mobile.html`) is frozen at export time.
* The journal pages are a 100-line tail per section, cached 30s on the
  measured machine — the panel, reading local disk, keeps its 500.
* `machines` is read from `cloud-infra/config.json` **and** `~/.ssh/config`
  on the MEASURED machine, so the fleet pages are empty when the phone is
  what is being measured rather than what is reading.

## Picking a machine

The drawer's first group, **machine**, lists every one of them and switches
the page to it — the phone reaches exactly one host, so "measure gcp-proxy"
means "ask this host to measure gcp-proxy", over the ssh hop only it can make.

A machine with no way in stays inert rather than offering a button that cannot
work: the host you are already on, and a VM declared with ip `TBD`.

That group shipped **empty** for as long as the app existed. The switcher is
built at export time as plain `<a href>` links between sibling files, which is
right for a directory of static reports on a USB stick and meaningless in an
APK — so the heading was there, the list was not, and the fleet sat one page
away on `machines`. It is filled from the envelope now.

## What counts as a machine

Eleven, from two sources that each know something the other does not:

| source | gives | machines |
|---|---|---|
| `config.json::vms` | provider, public IP, real name | 5 VMs |
| `config.json::native.wireguard.clients` | the v6, and the runners ssh has never heard of | gha-runner, health-runner, vault-backup |
| `~/.ssh/config` | which row is **this** machine, and the names you actually type | surface-nixos, phone, gcp-t4 |

The mesh is **four** networks, and a machine is on as many of them as it has
addresses for — wg0 `10.0.0.0/24` + `fd0c:1d00::/64`, the public tunnel
`10.1.0.0/24` + `fd0c:1d01::/64`. Every address a machine answers on travels
with it, so a peer lands on the page for each network it is genuinely on
rather than on whichever one its single recorded address happened to be.
