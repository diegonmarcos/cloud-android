# Cloud Contacts (Fossify Contacts fork)

The constellation address book: one app that merges every contact source and
hands "call / text / mail" off to the right constellation app.

- **Upstream**: [FossifyOrg/Contacts](https://github.com/FossifyOrg/Contacts)
  (GPL-3.0), pinned in `build.json::forks.contacts.pinned_tag`, materialized
  into the gitignored tracker `../ea_upstreams-sources/contacts-fossify/` and
  rebuilt from `patches/` on every build — never a long-lived divergent clone.
- **Identity**: `com.diegonmarcos.comms.contacts`, signed with the ONE shared
  constellation key (vault `A0_keys/providers/android/release.jks`).
- **CI**: `ship.yaml` → `1_cicd/src/cicd/ship-cloud-contacts.yml` publishes
  `ghcr.io/diegonmarcos/cloud-comms-contacts:latest` + the rolling
  `releases/latest/download/cloud-comms-contacts.apk` asset consumed by
  `ea_cloud-superapp ui.external_apps[cloud-contacts]`.

## Where the "merge" comes from

| Source | How it lands |
|---|---|
| Local phone | Native — Fossify reads ContactsContract + its private local store. |
| Gmail / Google | Native — the synced Google account appears as a contact source (view, edit, and import-target). |
| LinkedIn | Patch 0004 — import `Connections.csv` (Settings → Import contacts, or share the file to the app). |
| Instagram | Patch 0004 — import `followers_1.json` / `following.json` the same way. |

Social rows are converted to vCards (content-sniffed, then fed to the stock
VcfImporter), so the user picks the target source per import and the profile
URL lands as a tappable website that app-links into LinkedIn / Instagram.

## Where "call/text in each app" comes from

No custom IPC needed — actions fire standard intents, and the constellation
apps hold the roles: calls land in **cloud-dialer** (ROLE_DIALER, which itself
offers the VoIP/deep-link provider chooser), `mailto:` lands in **cloud-mail**,
SMS in the default SMS app, and WhatsApp/Signal/Telegram actions appear
natively via their ContactsContract sync entries.

## Patch series

1. `0001` rebrand launcher label → "Cloud Contacts"
2. `0002` unique applicationId via `-PAPPLICATION_ID` (namespace stays `org.fossify.contacts`)
3. `0003` remove commons' anti-repackage "fake app" dialog (false positive by construction)
4. `0004` LinkedIn/Instagram social import → stock vCard pipeline

## Build

```bash
./build.sh materialize-fork contacts   # clone upstream @ pin + apply patches
./build.sh build-fork contacts         # fork's own gradlew + constellation sign (needs SDK/vault)
./build.sh publish-fork contacts       # oras push → ghcr.io/diegonmarcos/cloud-comms-contacts
```

Deferred (add when needed): in-app self-updater patch (dialer patch 0005
pattern) — until then updates flow through the SuperApp Constellation
installer; CardDAV/Radicale sync — DAVx5/the cal stack already covers it.
