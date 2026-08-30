# libs:firewall — no-root per-app firewall

**Original / clean-room.** No upstream source is vendored, so the app keeps
its own license. `celzero/firestack` (MPL-2.0) is built as a separate aar for
the staged Phase-3 merge — see below.

## The model — per-app `AppRule` (parallel axes)

Each app gets an [AppRule] with independent axes that combine as a **SUM**
(blocked if ANY axis blocks):

| Axis | Meaning |
|------|---------|
| `wifi` / `cellular` | allow data on that physical transport |
| `background` | allow data while the app is in the background |
| `direction` | `NONE` / `ALL` / `IN` / `OUT` — the "general" block switch |
| `vpnMode` | **strong override**: `WG0_ONLY` / `WG_PUBLIC_ONLY` force the app VPN-only (ignoring wifi/cellular); `NONE` = off |

e.g. *"Wi-Fi all data, cellular none, no background"* =
`AppRule(wifi=true, cellular=false, background=false)`; *"cloud-VPN only"* =
`AppRule(vpnMode=WG0_ONLY)`. Rules are user data in SharedPreferences
([FirewallRules]) — the single source of truth; a default (all-allow) rule
clears the entry.

Per-app background = screen-on **and** the app is foreground (needs Usage
Access; degrades to screen-state only without it — [FirewallConditions]).

## Enforcement — shipping interim drain-engine

[FirewallVpnService] is a no-root local `VpnService`: the effective block set
is recomputed from each app's rule × live conditions ([FirewallDecider].
`interimBlocked`) and re-applied on every network/screen change. Blocked apps
fall into the tun and are drained/dropped; everyone else is excluded via
`addDisallowedApplication` and uses the network normally. The tun is
established whenever the firewall is enabled — even with zero apps currently
blocked — so the toggle **visibly** turns on.

**Interim limits** (need the netstack, Phase 3): direction `IN`/`OUT` can't be
split by drain-and-drop (only `ALL` is honoured — `FirewallDecider.interimBlocked`
skips `IN`), and the firewall + WireGuard tunnel are mutually exclusive (one
device VpnService slot).

## Staged Phase-3 merge (`phase3-firestack/`, outside the sourceset)

ONE firestack VpnService replaces BOTH the drain-engine and `GoBackend$VpnService`:
firestack owns the slot, WireGuard runs as an in-netstack proxy, and
[FirewallFlowBridge] decides each connection — unlocking true `IN`/`OUT` and
routing "vpn-only" apps through the WG proxy ("only under cloud VPN"). The
pure verdict layer ([FirewallFlowPolicy]) is realigned to `AppRule` and
unit-tested; the firestack-aar seams are marked runner-completion. Nothing in
the shipped build depends on it yet.

## Surfaced in the UI

About screen (`DevControlFragment`) → **Firewall** section, under **Battery**:
read-only info rows ([FirewallInfo], no permissions) + a gray **Firewall
Details** button → [FirewallDialog] (master switch + per-app **axis editor**:
Wi-Fi/Cellular/Background switches, a Cloud-VPN override selector, and a
Direction selector).

| File | Role |
|------|------|
| `FirewallRules.kt` | `AppRule` model + per-app rule persistence (single source of truth) |
| `FirewallDecider.kt` | pure block test — `block` (direction-aware) + `interimBlocked` (unit-tested) |
| `FirewallConditions.kt` | live transport / screen / per-app-foreground reader |
| `FirewallVpnService.kt` | **shipping** interim drain-engine (dynamic block-set) |
| `FirewallController.kt` | start/stop/consent facade (UI talks only to this) |
| `FirewallPrefs.kt` | master on/off desired state (enabled flag only) |
| `FirewallInfo.kt` | read-only status for the About rows |
| `FirewallDialog.kt` | control screen — master switch + per-app axis editor |
| `phase3-firestack/FirewallFlowPolicy.kt` | pure per-flow verdict incl. cloud-VPN routing (unit-tested) |
| `phase3-firestack/FirewallFlowBridge.kt` | firestack `FlowListener` → decision → `Mark` |
| `phase3-firestack/FirestackTunnelService.kt` | merged VpnService (netstack + filter + WG) |
| `phase3-firestack/FirestackBridgeAssembly.kt` | **runner-completion seam** for the `Bridge` union |
| `phase3-firestack/CloudVpn.kt` | `CloudVpnProvider` seam (app injects WG state; no lib→app dep) |

## Phase-2 firestack aar build (wired, not yet consumed)

`build.sh firestack` hermetically builds `celzero/firestack` (Go/gomobile) →
`libs/firewall/firestack/firestack.aar` (flatDir in `settings.gradle`, folded
into this lib — not a loose top-level module), data-driven from
`build.json::upstreams.firestack`. Nothing consumes the firestack aar until
Phase 3, so a plain `build.sh build`/`test` does NOT trigger the Go build.

## Activating Phase 3 (on the build runner)

1. `build.sh firestack` — build the aar.
2. `libs/firewall/build.gradle`: add `implementation(name:'firestack', ext:'aar')`.
3. Move `phase3-firestack/*.kt` into `src/main/...` (and `.staged` test into `src/test/...`).
4. Complete `FirestackBridgeAssembly` + `registerCloudVpn` against the aar's
   generated `Bridge` union (mirror RethinkDNS `GoVpnAdapter`/`GoIntraListener`).
5. Manifest: register `FirestackTunnelService`, retire the two old VpnServices.
6. App injects a `CloudVpnProvider` (from `libs:net` WG state) into the controller.
