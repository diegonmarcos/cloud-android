# libs/firewall/firestack — prebuilt netstack AAR (build output, not source)

This directory holds **`firestack.aar`** — the gomobile-built
[`celzero/firestack`](https://github.com/celzero/firestack) gVisor-netstack +
WireGuard-proxy engine (MPL-2.0, Go). It is a **build artifact**, produced by
`./build.sh firestack` and **gitignored** (never committed) — same doctrine as
`libs/net`'s `libwg-go.so`.

It is **not** a gradle module (absent from `build.json::modules`). The aar is
self-contained (gomobile bundles all Go deps), so Phase 3 consumes it via a
central `flatDir` + a plain aar dependency — no wrapper module needed.

## How it's built (`build.sh firestack`)

Data-driven from `build.json::upstreams.firestack.build`:

1. `build.sh sync-firestack` clones/updates the tracker at
   `$UNIX_REPO/ea_net-firestack` (pinned to `ref`, default `n2`).
2. `build.sh firestack` self-downloads the pinned Go (`go_version`,
   checksum-verified into `.cache/golang/`), then runs firestack's **own**
   `make <make_target>` inside the Nix devShell (for `ANDROID_HOME` + NDK).
   That target go-installs `gomobile`/`gobind`, runs `gomobile init`, and
   `gomobile bind`s → `build/intra/tun2socks.aar`.
3. The aar is copied here as `firestack.aar` and sanity-checked (must contain
   `classes.jar` with `com.celzero.firestack` bindings).

## Phase 3 consumption (not wired yet)

```gradle
// settings.gradle → dependencyResolutionManagement.repositories:
flatDir { dirs "${rootDir}/libs/firewall/firestack" }
// libs/firewall/build.gradle:
implementation(name: 'firestack', ext: 'aar')
```

Then the firestack `Bridge.Flow()` decision hook calls `FirewallDecider`
(direction-aware) and routes allowed flows via the WireGuard proxy — one
`VpnService` replacing both the drain-engine and `GoBackend$VpnService`.

**Reproducibility caveat:** firestack's Makefile pins `gomobile@latest`
(upstream's choice) — the one non-hermetic edge, tracked upstream.
