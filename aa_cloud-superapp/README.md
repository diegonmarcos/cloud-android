# Cloud SuperApp

Modularized monolith Android app — one APK, gradle multi-module. Each domain (mail, calendar, RSS, WireGuard, cloud ops, vault, chat, C3 ops) lives in its own focused library module; the launcher shell in `app/` ties them together.

```
ea_cloud-superapp/
├── build.sh            ← universal dispatcher (build/dev/test/clean/ship)
├── build.json          ← data-driven module graph + toolchain pins
├── flake.nix           ← Nix devShell: JDK 17 + Gradle 8.7 + AGP 8.5 + Android SDK
├── settings.gradle     ← mirrors build.json::modules
├── build.gradle        ← root (plugin versions only)
├── gradle.properties   ← parallelism, AndroidX, caching
├── app/                ← launcher shell (com.diegonmarcos.superapp)
└── libs/
    ├── core/           ← shared models, DB, theming, prefs, auth
    ├── mail/           ← FairEmail-derived + JMAP (extends Stalwart JMAP)
    ├── cal/            ← CalDAV → Radicale (cal.diegonmarcos.com)
    ├── feed/           ← RSS + ntfy
    ├── net/            ← WireGuard tunnel (wireguard-android-derived)
    ├── ops/            ← c3-api dashboards, health, dagu trigger
    └── vault/          ← passwords + TOTP (Vaultwarden-compatible)
```

## Build

```bash
./build.sh shell           # enter Nix devShell (gradle + sdk + jdk)
./build.sh build           # produces dist/superapp-debug.apk
./build.sh ship            # build + side-load via adb
```

All build calls route through `nix develop` per the flake — host tools are never required. Set `BYPASS_NIX=1` only for IDE work.

## Status

Phase 0: skeleton — empty modules, "Hello Diego" main screen. Verifies the build chain works. Next: cherry-pick FairEmail's protocol+sync into `libs:mail/` and add JMAP transport (`rs.ltt.jmap.client`).

Upstream FairEmail tracking clone lives at `../ea_mail-fairmail/` (shallow, read-only mirror — `git pull` to surface upstream fixes).

## License

FairEmail-derived code is GPLv3. Anything we ship that includes `libs:mail/` (which inherits FairEmail's sources) must remain GPLv3. New diego-original modules (`libs:cal`, `libs:feed`, `libs:net`, `libs:ops`, `libs:vault`, `libs:core`, `app/`) can be licensed independently, but for monorepo simplicity we treat the whole APK as GPLv3.

## Why modularized monolith

- **Single icon / unified UX** — looks/feels like one app to the user.
- **Internal modularity** — each `libs/<x>/` has its own deps, tests, ProGuard; can't cross-import private code.
- **Per-domain Nix flake fragment + `build.json`** — same data-driven engine pattern as the cloud services repo, just at the Android layer.
- **Dynamic Feature Module path** — heavy modules (WG native libs, ML for spam) can be made on-demand later.

Alternatives considered:
- **Constellation (separate APKs)** — cleaner upstream sync, but multi-icon UX and IPC overhead. Skipped.
- **Single monolith app/ module** — what FairEmail is today. Doesn't scale to 7 domains and tightly couples everything.
