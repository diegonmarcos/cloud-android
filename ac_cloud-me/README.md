# Cloud Me

The personal-administration half of the constellation.

Cloud SuperApp is the launcher for everything the *cloud* runs. Cloud Me is the
launcher for everything the *person* runs: paperwork and money, what is due,
the public profile, travel, study — and health.

```
┌──────┬────────┬──────┬──────────┬──────────┐
│ Buro │ Agenda │  Me  │ Projects │ Studying │   ← bottom nav
└──────┴────────┴──────┴──────────┴──────────┘
  ☰ drawer: Health            ⚙ toolbar: Configs
```

## What it stores

Almost nothing. That is the design.

| Surface | Where the data actually lives |
|---|---|
| Buro → Docs | Cloud IDE (files) |
| Buro → IDs | Cloud Wallet (the card deck) |
| Buro → Vault | Cloud Vault (Vaultwarden) |
| Buro → Accounting | `libs:fin`, from `build.json::ui.myfin_mock` |
| Agenda | `libs:cal` — CalDAV events + VTODOs, read locally |
| Me | `libs:wallet` — the same Me tab Cloud Wallet shows |
| Health | `libs:health` — Health Connect, on-device only |

A second copy of a card, a password or a health record would be a second thing
to keep correct. Cloud Me indexes; the app that owns the data keeps it.

## Navigation is data

Every menu in the app is `build.json::ui.sections`, baked into
`BuildConfig.UI_SECTIONS_B64` and decoded once by `Sections.kt`. There is no
`res/menu`, no static tab list and no hardcoded tile array anywhere in the
Kotlin.

- Move Health into the bottom bar → `"bottom_nav": true` plus an `order`.
- Add a tab → one `pages` entry and the matching `stack_<page id>`.
- Add content → a block in that stack.

Block kinds the renderer understands: `section_title`, `note`, `stats`,
`cards`, `link_grid`, `about`, `permissions`, and `fragment` (hands the whole
page to a library module).

## Modules

Every `libs:*` is shared **by reference** out of `ab_cloud-libs-shared/libs`
via `build.json::modules[].dir` — this app owns no library source. A fix in
`libs:health` or `libs:wallet` lands here on the next build with no copy to
resync.

## Build

```sh
./build.sh build      # debug APK → dist/Cloud-Me.apk
./build.sh release    # signed release
./build.sh dev        # install + launch on the connected device
```

CI: `.github/workflows/ship-cloud-me.yml` (generated from
`1_cicd/src/cicd/ship-cloud-me.yml`) builds both ABIs, pushes the APK to GHCR
via ORAS and attaches it to the rolling GitHub Release. The app self-updates
from that image through `libs:updater`.

Fleet membership is automatic: `aa_cloud-superapp/data/regen.sh` scans every
sibling `build.json` and admits anything declaring `android.application_id`
plus `release.ghcr`, so Cloud Me registers itself in the constellation store
with no hand-maintained list to update.
