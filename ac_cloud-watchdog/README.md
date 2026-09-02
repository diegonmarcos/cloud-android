# cloud-watchdog

The my-watchdog panel, on a phone. **This app invents no page and no number.**

The interface ships INSIDE the APK: `my-watchdog-tui app-shell` renders the
whole drawer, every panel frame and every table header against an empty
machine, and that page is baked in as `watchdog-app.html`. The app therefore
opens whether or not it can reach anything — the failure that made it show a
terminal error where a dashboard should be was the UI being a property of
having measured something.

The numbers arrive separately. It ssh's to nix-on-droid on `127.0.0.1` (Termux
as fallback), asks for one envelope, and hands it to the page.

```
  APK opens ──► watchdog-app.html          (UI, baked in, no machine in it)
  WebView   ──► WatchdogBridge ──ssh 127.0.0.1:8022──► my-watchdog-tui envelope
  WebView   ◄── window.__wdRender(json)  ◄──────────── {snapshot, machines, …}
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

## If the app shows an empty interface

**Every page blank, on load, before you touch anything** was a crash in the
shell's own script: with no data yet it drew the legacy overview boxes against
the empty placeholder shell and called `.toFixed` on a `{}`, which threw and
took the rest of the script — the nav handlers included — down with it. The
overview shows a "waiting for a machine" state now until a machine answers, and
the number formatters coerce non-numbers to 0 so one bad field can never blank
the page again. (fixed 2026-09-02)

## If the app cannot reach a machine

It is not reaching the machine, and until 2026-09-02 the usual reason was a
bug in the panel rather than anything on the phone.

`snapshot` decided whether the sampler had published by asking whether
`host_info.host` was empty. That is read from `/proc/sys/kernel/hostname`,
which returns an **empty string inside nix-on-droid's proot** — so every
published snapshot on this device looked like no snapshot, `snapshot` exited 1,
and the app got an error instead of an envelope. The shell it ships with stayed
exactly as baked: no fleet, no numbers, no pages. Every "the APK has not
changed" was this.

The liveness test is `ts` now, which the publisher writes on every tick and
nothing else writes, and the sampler names the device four ways
(`/proc/sys/kernel/hostname` → `/etc/hostname` → `$HOSTNAME` →
`/system/bin/getprop ro.product.model`, which answers `SM-G996B` here).

To check by hand, from a shell in the env:

```sh
export XDG_RUNTIME_DIR=$HOME/.cache/xdg-runtime
~/.cache/cloud-watchdog/bin/my-watchdog-tui snapshot | head -c 200
```

An envelope means the app has everything it needs. An error names what is
missing.

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
