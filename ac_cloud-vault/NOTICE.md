# NOTICE — Cloud Vault

Cloud Vault is a rebranded fork of [bitwarden/android](https://github.com/bitwarden/android),
built from the `app` module (the password manager; **not** the `authenticator`
module) at upstream tag `v2026.7.1-bwpm`.

## License

The Bitwarden Android SDK crates this app depends on (`com.bitwarden:sdk-android`,
built from `crates/bitwarden-uniffi` in [bitwarden/sdk-sm](https://github.com/bitwarden/sdk-sm))
are dual-licensed `GPL-3.0-only OR LicenseRef-Bitwarden-SDK` (SPDX `OR` = the
licensee's choice). Cloud Vault elects **GPL-3.0-only**. No directory named
`bitwarden_license` (the marker for the commercial license grant) exists under
`crates/bitwarden-uniffi`, so the GPL-3.0 election is unencumbered.

Upstream's own `LICENSE.txt` (GPL-3.0) is kept intact and unmodified at the
repository root; this NOTICE does not replace or supersede it.

## Trademarks

"Bitwarden" and the Bitwarden shield logo are trademarks of Bitwarden, Inc.
Trademark rights are **not** granted by the GPL-3.0 license and are **not**
used by this fork:

- App name changed to "Cloud Vault" (`app_name` in
  `app/src/{main,beta,release}/res/values/strings_non_localized.xml`).
- Launcher icon replaced with an original padlock mark — no Bitwarden
  artwork is reused (`app/src/{main,beta,release}/res/drawable/ic_launcher_*`,
  `app/src/main/res/values/ic_launcher_background.xml`).
- `applicationId` changed from `com.x8bit.bitwarden` to
  `com.diegonmarcos.cloudvault`.

## What changed vs. upstream

See the patch series in `ac_cloud-vault/patches/` in the
[diegonmarcos/cloud-infra-desktop](https://github.com/diegonmarcos/cloud-infra-desktop) repository
(directory `ac_cloud-vault/`) for the exact, reviewable diff against the
pinned upstream tag. In summary: rebranding (this NOTICE's subject) and
pre-provisioning the default environment to point at this fork's own
self-hosted [Vaultwarden](https://github.com/dani-garcia/vaultwarden)
instance instead of Bitwarden's hosted cloud service.

## Source

- This fork's patches + build machinery: https://github.com/diegonmarcos/cloud-infra-desktop
  (`ac_cloud-vault/`).
- Unmodified upstream source: https://github.com/bitwarden/android
  (tag `v2026.7.1-bwpm`).
