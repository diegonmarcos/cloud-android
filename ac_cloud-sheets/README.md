# ac_cloud-sheets — Collabora Office (MIRROR)

This directory contains **no source code**, and that is the design.

Every other `ac_cloud-*` directory in this monorepo holds a tree we compile.
Collabora Office is the exception: its engine *is* LibreOffice core, built from
Collabora's own Gerrit with an NDK megabuild over the entire `external/` tree.
That is a toolchain we do not carry, hours of compute we do not want to spend
per push, and the artifact still could not legitimately be signed as
`com.collabora.libreoffice` by us.

So `ship-cloud-sheets.yml` **mirrors** instead:

1. downloads the official APK from Collabora's F-Droid repo,
2. verifies it byte-for-byte against the `sha256` pinned in `build.json`,
3. republishes those *exact, unmodified* bytes as
   - GH Release asset `Cloud-Sheets.apk` on the rolling `latest` tag, and
   - `ghcr.io/diegonmarcos/cloud-sheets:latest` (ORAS, media type
     `application/vnd.android.package-archive`),

so the Constellation AppStore installs and auto-updates it like any other fleet
member. Installing ours is byte-identical to installing from Collabora's own
repo — same package id, same upstream signature.

## The pin

`build.json::upstream` is the security boundary. An unpinned mirror is a
supply-chain hole: whatever upstream happened to serve would go out under our
name. A sha256 (or size) mismatch **fails the job** and publishes nothing.

Currently pinned:

| field        | value                                                          |
|--------------|----------------------------------------------------------------|
| package      | `com.collabora.libreoffice`                                    |
| versionName  | `25.04.9.1`                                                    |
| versionCode  | `115`                                                          |
| apk          | `collabora-office-mobile-25-04-release-arm64-v8a-2026-03-03.apk` |
| size         | `278215660`                                                    |
| sha256       | `761eefbb71aabb788843bffa4deb046f89e910e30896be1cc8ab522460a8da5c` |
| abi          | `arm64-v8a` only (upstream ships no x86_64)                    |
| minSdk       | `26`                                                           |
| license      | `MPL-2.0`                                                      |

## Bumping the version

Read the new entry out of
<https://www.collaboraoffice.com/downloads/fdroid/repo/index-v1.json> and update
`version_name`, `version_code`, `apk_name`, `url`, `sha256` and `size` together
in **one** commit. Never update the sha alone — a sha that no longer matches its
declared version is a pin that documents nothing.
