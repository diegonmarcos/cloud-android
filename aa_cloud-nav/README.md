# Cloud Nav

Dedicated maps / navigation / tracker Android app — lifted out of Cloud
SuperApp's `libs:maps`. Same engine pattern as the SuperApp (modularized
monolith, `build.sh` + `build.json` + Nix devShell), but a focused
Google-Maps-style shell.

```
ea_cloud-nav/
├── build.sh            ← universal dispatcher (build/dev/test/clean/ship/emulator)
├── build.json          ← data-driven: module graph + toolchain + UI (tabs/islands/places) + maps providers
├── flake.nix           ← Nix devShell: JDK 17 + Gradle 8.10 + AGP 8.7 + Android SDK
├── settings.gradle     ← mirrors build.json::modules
├── app/                ← Google-Maps shell (com.diegonmarcos.cloudnav)
└── libs/
    ├── core/           ← shared interfaces + stores (was SuperApp libs:core)
    └── maps/           ← MapLibre renderer + GPS tracker + Stop store + Timeline + MyTrips
```

## Shell

Top **search bar** (multi-stop routing query) + small **island chips**
(Home / Work / Saved / Recent / Share) under it, content area, and a bottom
nav with five tabs:

| Tab | Fragment | What it does |
|-----|----------|--------------|
| **Routes** | `routes.RoutesFragment` | Live MapLibre map + multi-stop route panel (start = your location). |
| **Navigation** | `routes.NavigationFragment` | Driving view + turn-by-turn banner. |
| **Timeline** | `maps.MapsTimelineTabsFragment` | Tracker data by day / by stop (lifted from libs:maps). |
| **Places** | `places.PlacesFragment` | Browse POIs by category (Bars, Beaches, Gyms, ATMs, …). |
| **Configs** | `configs.ConfigsFragment` | Tracker settings (`MapsConfigFragment`) + the extensive **About** page (`DevControlFragment`). |

Tabs, search islands and place categories are **data-driven** from
`build.json::ui.*` (decoded by `NavConfig`) — adding one is a build.json edit,
no Kotlin change. Routing engine, turn-by-turn guidance and the POI-provider
query for Places are the next phases; the current UIs are the wired scaffold.

## About page

`configs.DevControlFragment` is ported from Cloud SuperApp's DevControl —
the same extensive, full-of-data page (App / APK / Device / Stack / Storage /
Permissions / Battery & Energy / Memory & CPU / Network / Wi-Fi / Profile /
Dev-Control HTTP), rendering **Cloud Nav's own** data. The four cross-subsystem
sections (VPN/WireGuard, Health Connect, Floating Nav, Shizuku) are omitted —
they belong to the SuperApp launcher, not a navigation app.

## Build

```bash
./build.sh shell      # enter Nix devShell (gradle + sdk + jdk)
./build.sh build      # produces dist/Cloud-Nav.apk
./build.sh ship       # build + side-load via adb
./build.sh emulator   # boot arm64 AVD, then `ship` into it
```

All build calls route through `nix develop` per the flake — host tools are
never required. Set `BYPASS_NIX=1` only for IDE work.

## Relationship to Cloud SuperApp

`libs:maps` was **moved** here (not copied) — SuperApp no longer ships maps.
`libs:core` is a copy (shared base infra). Package roots repackaged
`com.diegonmarcos.superapp.*` → `com.diegonmarcos.cloudnav.*`.
