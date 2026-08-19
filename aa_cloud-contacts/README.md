# Cloud Contacts — the multi-channel people hub

Native constellation app (NOT a fork). A person is a set of **channels**, and
the person screen is a **channel switchboard**: WhatsApp, Telegram, Matrix
(→ cloud-matrix), Cloud Chat (Mattermost), Cloud Mail, SMS, Cloud Dialer,
LinkedIn, Instagram — tap a channel, land in that conversation in that app.
There is deliberately **no default call action**: calling is one channel among
many, not the app's identity.

## Architecture (cloud-news pattern)

- `app/` — thin WebView shell: `MainActivity` (31-line class: WebView +
  permission/SAF launchers) + `ContactsBridge` (Kotlin↔JS, JSON-string API) +
  `assets/contacts.html` (the whole UI, HTML/Tailwind, offline, dark).
- `libs/contacts` — the domain: `DeviceContacts` (ContactsContract reader:
  local + synced accounts such as Gmail), `SocialImport` (LinkedIn
  `Connections.csv` + Instagram followers/following JSON, content-sniffed),
  `SocialStore` (JSON file store), `MergeEngine` (union-find identity merge by
  normalized email/phone/name), `Channels` (registry resolution).
- `libs/core`, `libs/updater` — shared constellation libs (GHCR self-updater,
  data-driven from `build.json::release`).
- **`build.json::channels.registry`** — THE channel registry: kind
  (phone/email/url:domain/handle:key), brand color, glyph, action URI
  templates, optional constellation package hint. Baked into
  `BuildConfig.CHANNELS_B64`; adding a channel is a JSON edit, never Kotlin.

## Sources merged into one identity

| Source | How |
|---|---|
| Local phone | ContactsContract (runtime READ_CONTACTS; app works without it) |
| Gmail / accounts | Same query — synced accounts appear as `gmail:<account>` provenance |
| LinkedIn | Import `Connections.csv` (Settings → Data privacy → Get a copy of your data) |
| Instagram | Import `followers_1.json` / `following.json` (Accounts Center → Download your information) |

## Build / ship

```bash
./build.sh build          # Nix devShell → signed debug APK (dist/Cloud-Contacts.apk)
./build.sh ship           # build + oras-push (GHCR) + gh-release
```

CI: `1_cicd/src/cicd/ship-cloud-contacts.yml` → `ghcr.io/diegonmarcos/cloud-contacts`
+ rolling `releases/latest/download/Cloud-Contacts.apk` (consumed by
`ea_cloud-superapp ui.external_apps[cloud-contacts]`). Same shared
constellation signing key (`cloud-vault A0_keys/providers/android/`).
