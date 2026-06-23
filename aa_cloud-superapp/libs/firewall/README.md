# libs:firewall — no-root per-app outbound firewall

**Original / clean-room.** No upstream source is vendored in v1, so the app
keeps its own license.

## What it does (v1)

A minimal **no-root, no-native-code** per-app outbound firewall built on a
local `android.net.VpnService`:

- `establish()` builds a tun with a default route, so the OS routes app
  traffic **into** the tun;
- every **allowed** app is added via `addDisallowedApplication()` → it
  **bypasses** the tunnel and uses the real network normally;
- every **blocked** app therefore falls **into** the tun, where its packets
  are drained and discarded → its connections time out (**blocked**).

If there are zero blocked apps the service tears down instead of holding the
device VPN slot for nothing.

## Surfaced in the UI

About screen (`DevControlFragment`) → **Firewall** section, directly under
**Battery**: read-only info rows (`FirewallInfo`, sourced from
`ConnectivityManager`/`Settings`, no permissions) + a gray **Firewall
Details** button → `FirewallDialog` (master enable + per-app block toggles).

| File | Role |
|------|------|
| `FirewallVpnService.kt` | the no-root engine (tun + drain) |
| `FirewallController.kt` | start/stop/consent facade (UI talks only to this) |
| `FirewallPrefs.kt` | block-set + enabled state in SharedPreferences (user data) |
| `FirewallInfo.kt` | read-only status for the About rows |
| `FirewallDialog.kt` | the control screen (DialogFragment) |

## Single VPN slot

While the firewall holds the device VPN slot, the `libs:net` WireGuard
tunnel cannot run at the same time — v1 treats them as mutually exclusive
and reports it in the info rows.

## Cherry-pick reference (future, complex tooling)

`build.json::upstreams.firewall` → **RethinkDNS** (`celzero/rethink-app`,
Apache-2.0, Kotlin). It is **reference only** — nothing is copied in v1.
Pull the clone for cherry-picking with:

```sh
./build.sh sync-firewall
```

Cherry-pick targets for later: DoH/DoT/DNSCrypt DNS filtering, per-connection
tracker, and WireGuard proxying via its Go `firestack` engine — the latter
also resolves the single-VPN-slot limitation above.
