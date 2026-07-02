# libs:firewall — no-root per-app outbound firewall

**Original / clean-room.** No upstream source is vendored in v1, so the app
keeps its own license.

## What it does (v2 — conditional per-app rules)

A **no-root, no-native-code** per-app firewall built on a local
`android.net.VpnService`. Each app gets a **policy**: a list of rules that
block traffic under specific conditions.

A **rule** = block `direction` (ALL / IN / OUT) while on one of `transports`
(Wi-Fi / cell / VPN / other) **and** in one of `energy` states (active /
background). Predefined building-block rules ship as DATA in
`assets/firewall_presets.json`; the user composes per-app policies from them
(e.g. *"Wi-Fi only, no background"* = `wifi-only` + `foreground-only`).

Enforcement is the **merged firestack netstack** ([FirestackTunnelService]):
ONE `VpnService` runs the gVisor netstack + per-app filtering + the WireGuard
cloud VPN together. Firestack calls [FirewallFlowBridge] per connection
(`flow` = outbound, `inflow` = inbound), which resolves uid→package, reads the
policy + live conditions, and returns a proxy id — `Block` (drop) / `Exit`
(direct) / a WG proxy id (route via cloud VPN). Energy is per-app: `ACTIVE`
only when the screen is on **and** the app is foreground (needs Usage Access;
degrades to screen-state only without it).

Because decisions are per-flow, rules re-evaluate continuously — no
teardown/re-establish on network or screen changes.

### The cloud-VPN superpower

"Cloud-VPN only" apps (policy blocks every physical transport but allows VPN)
are **routed through the WireGuard proxy** when it's up (so they're
effectively always-under-VPN) and **blocked** when it's down — see
[FirewallFlowPolicy]. This is what the single-VpnService merge unlocks that a
plain firewall can't.

The decision core (`FirewallDecider` / `FirewallFlowPolicy`) is pure and
unit-tested (`FirewallDeciderTest`, `FirewallFlowPolicyTest`); the firestack
adapter is the only netstack-coupled layer.

## Surfaced in the UI

About screen (`DevControlFragment`) → **Firewall** section, under **Battery**:
read-only info rows (`FirewallInfo`, no permissions) + a gray **Firewall
Details** button → `FirewallDialog` (master switch + per-app **preset picker**).

| File | Role |
|------|------|
| `assets/firewall_presets.json` | predefined rule building-blocks (DATA) |
| `FirewallRules.kt` | rule model + preset loader + per-app policy persistence |
| `FirewallDecider.kt` | pure direction-aware block test (unit-tested) |
| `FirewallFlowPolicy.kt` | pure per-flow verdict incl. cloud-VPN routing (unit-tested) |
| `FirewallConditions.kt` | live transport / screen / per-app-foreground reader |
| `FirewallFlowBridge.kt` | firestack `FlowListener` → decision → `Mark` |
| `FirestackTunnelService.kt` | merged VpnService (netstack + filter + WG) |
| `FirestackBridgeAssembly.kt` | **runner-completion seam** for the `Bridge` union |
| `CloudVpn.kt` | `CloudVpnProvider` seam (app injects WG state; no lib→app dep) |
| `FirewallController.kt` | start/stop/consent facade + `CloudVpnProvider` holder |
| `FirewallPrefs.kt` | master on/off desired state (enabled flag only) |
| `FirewallInfo.kt` | read-only status for the About rows |
| `FirewallDialog.kt` | control screen — master switch + per-app preset picker |

## Build dependency

`libs:firewall` consumes the firestack aar (`build.sh firestack` →
`libs/firestack/firestack.aar`, flatDir in `settings.gradle`). `build.sh`
auto-builds it on demand (`_ensure_firestack`). See `libs/firestack/README.md`.

## Runner-completion (the honest edges)

`FirestackBridgeAssembly` + the exact WG-proxy register call
(`FirestackTunnelService.registerCloudVpn`) compile only against the built
aar's generated `Bridge` union — complete them on the runner, mirroring
RethinkDNS's `GoVpnAdapter`/`GoIntraListener`. Everything else is
source-grounded. The app must also call `FirewallController.setCloudVpn(...)`
to enable WG routing (until then, "vpn-only" apps simply block off-VPN).
