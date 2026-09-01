# fa_garmin-watchface

Garmin Connect IQ watch faces — **multi-design monorepo**. Each design is a
self-contained CIQ project under `faces/<id>/` carrying its **own geometry** in
`faces/<id>/design.json`. One universal engine (`build.sh`), one declarative
SDK (`flake.nix`), publishing that mirrors `aa_cloud-superapp`.

## Layout

```
fa_garmin-watchface/
├── build.sh            universal, design-aware engine (the ONLY build interface)
├── build.json          project config: toolchain pins, device targets, release{}
├── flake.nix           declarative Connect IQ SDK + devShell (Nix way, no Docker)
├── lib/gen-design.py   design.json → DesignConfig.mc + launcher_icon.png (data-driven)
└── faces/
    └── 2-circles/      ← one folder PER DESIGN (add designs = add folders)
        ├── design.json   THIS design's geometry (every number is a knob) + uuid
        ├── manifest.xml  CIQ identity: uuid, type=watchface, product=fenix8pro47mm
        ├── monkey.jungle CIQ project def (source + source/gen + resources)
        ├── source/       TwoCirclesApp.mc, TwoCirclesView.mc (renderer; NO hardcoded geometry)
        │   └── gen/      DesignConfig.mc  ← GENERATED (gitignored)
        └── resources/    strings + launcher_icon.png (GENERATED, gitignored)
```

## Build (always via the engine)

```bash
./build.sh gen        [design]   # design.json → DesignConfig.mc + launcher icon
./build.sh build      [design]   # → dist/<design>/<design>.prg  (sideload to watch)
./build.sh package    [design]   # → dist/<design>/<design>.iq   (store upload)
./build.sh sim        [design]   # build + boot the connectiq simulator
./build.sh oras-push  [design]   # .prg → GHCR as OCI artifact
./build.sh gh-release [design]   # .prg → rolling GitHub release (stable URL)
./build.sh clean      [design]
```
`[design]` defaults to `build.json::build.default_design` (`2-circles`).

## Target

`fenix8pro47mm` — fēnix 8 Pro 47/51mm/MicroLED, 454×454 round AMOLED. The face has
**no settings** (faithful to the original "2 Circles"), so it's unaffected by the
Focus-Mode watch-face-selection caveat on the Fenix 8 Pro.

## The "2 Circles" design

Replica of Schiefersoft's *2 Circles* (CIQ `a8e08d3f-…`): *"Large circle: minutes,
Small circle: hours, No settings."* The round screen is the minute scale; the small
HOURS circle is pinned to the current-minute point (so it travels with the minutes)
and carries a short hour hand + 12 rim ticks; the big MINUTES circle orbits with it.
**Every geometry value lives in `faces/2-circles/design.json`** and is a tuning knob —
the renderer reads generated constants, never hardcodes. Tune JSON → `build.sh build`
→ verify on the watch.

## One-time setup (Nix)

`flake.nix` is the declarative toolchain. Two fixed-output hashes are bootstrapped
once (standard Nix FOD flow — Nix prints the real hash on first run):

1. `nix develop` → replace `vendorHash = lib.fakeHash` in `flake.nix` with the hash
   Nix reports for `connect-iq-sdk-manager`, commit.
2. The SDK is then materialised into `.ciq-sdk/` (gitignored), pinned by the
   sdk-manager lockfile. `monkeyc`/`connectiq`/`monkeydo` land on `PATH`.

## CI / publishing

`1_cicd/src/cicd/ship-garmin-watchface.yml` (deployed to `.github/workflows/`
by `9_others/build.sh`). On push to `main` under `fa_garmin-watchface/**`: discovers
designs from `build.json`, builds each `.prg` on `ubuntu-latest` (x86, `BYPASS_NIX=1`),
pushes to GHCR via ORAS, and attaches to a rolling `latest` GitHub release. Same flow,
data-driven, as `aa_cloud-superapp`.

Auth model (verified against the CLI source): the SDK itself downloads from a
**public** Garmin CDN (no login), but the **device definitions** (`device download`)
come from an **authenticated** endpoint. monkeyc can't compile for `fenix8pro47mm`
without those device files — so the **build** needs a Garmin login, while the deploy
(GHCR + GH release) only uses the automatic `GITHUB_TOKEN`.

CI therefore needs **one prerequisite**: GHA repo secrets `GARMIN_USERNAME` +
`GARMIN_PASSWORD` (the SDK manager's `login`). ⚠️ The Garmin account must **not**
have 2FA/MFA — headless SSO login can't answer an MFA challenge.
(`build.json::toolchain.agreement_hash` is an OPTIONAL pin, not required — `agreement
accept` is public + non-interactive.)

The workflow only installs the CLI + supplies the creds; `build.sh::ensure_sdk` runs
`agreement accept → sdk set (public) → login → device download` (the universal engine
owns the build, not the workflow).

## Signing

One stable RSA developer key signs every design (constant signature across
re-publishes). Canonical key in vault (`A0_keys/providers/garmin/signing.secrets.yaml`,
sops); CI caches a generated key. `build.sh secrets` materialises it to
`dist/developer_key.der` (gitignored, never committed).
