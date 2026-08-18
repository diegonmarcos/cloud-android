# Cloud Vault

Standalone Android password-manager client. Two build paths live in this
app. **The shipping app (since 2026-08-18) is the rebranded source fork of
Bitwarden's Android app** — CI's push-triggered job builds it and publishes
it as `Cloud-Vault.apk` (GHCR `:latest` + the GH release rolling tag), which
is the URL the superapp's constellation store points at. `build.sh`'s
default commands (`build`/`release`/`ship`/...) still produce the legacy
in-tree WebView wrapper around `vault.diegonmarcos.com` (self-hosted
Vaultwarden), now a manual-only CI fallback (`build_webview=true` on
workflow dispatch).

## Fork: vault (Bitwarden Android — password manager)

- **Upstream**: https://github.com/bitwarden/android.git, pinned tag
  `v2026.7.1-bwpm`. The repo also ships `-bwa` tags (Bitwarden
  Authenticator) — a **different app**, never use those. Module `app`
  (applicationId `com.x8bit.bitwarden` upstream) is the password manager;
  module `authenticator` is the other product and is not touched.
- **App id**: `com.diegonmarcos.cloudvault` (patched from `com.x8bit.bitwarden`)
- **Tracker**: `../ea_upstreams-sources/vault-bitwarden/` (gitignored;
  `materialize-fork` clones it at the pin and never trusts a pre-existing
  tracker HEAD)
- **Flavor**: `fdroid` (dimension `mode`) — the Google-free flavor.
  Firebase/Crashlytics/ML-Kit/Play-Billing are `standardImplementation`-only;
  fdroid ships no-op source-set overrides and disables the
  GoogleServices/Crashlytics gradle tasks in `afterEvaluate`.
- **License**: the SDK crates (`com.bitwarden:sdk-android`, built from
  `crates/bitwarden-uniffi` in bitwarden/sdk-sm) are dual-licensed
  `GPL-3.0-only OR LicenseRef-Bitwarden-SDK`. We elect **GPL-3.0-only** (no
  `bitwarden_license` directory exists under `crates/bitwarden-uniffi`, so
  the commercial grant doesn't apply here anyway). Upstream's `LICENSE.txt`
  is kept intact. See `patches/0003-*` (NOTICE.md) for the full statement.
  Bitwarden trademarks are **not** used — see the rebrand patch.

### Fork machinery

Same engine ea_cloud-mail uses, ported verbatim into this app's `build.sh`
(`_fork_json`, `step_materialize_fork`, `step_build_fork` + their signing/
identity-assert/upstream-apk helpers): clone upstream at the pinned tag into
the gitignored tracker dir, apply the committed `patches/*.patch` series with
`git am`. Same input → same working tree, always — never a long-lived
divergent clone.

```
./build.sh materialize-fork vault   # clone @ pin + apply patches/
./build.sh build-fork vault         # fork's own gradlew :app:assembleFdroidRelease
```

### Patch series (`patches/`)

1. `0001-vault-rebrand-...patch` — `app_name` → "Cloud Vault" in the three
   buildType-scoped `strings_non_localized.xml` (main/beta/release);
   `applicationId` → `com.diegonmarcos.cloudvault`; launcher icon replaced
   with an original padlock mark (main **and** release **and** beta
   drawable overrides — AGP merges the buildType-specific
   `ic_launcher_foreground`/`ic_launcher_monochrome` over main's, so all
   three needed patching for the actual `fdroid`+`release` build to stop
   shipping Bitwarden's shield artwork).
2. `0002-vault-pre-provision-...patch` — first-run default environment
   changed from `Environment.Prod.Us` (Bitwarden's hosted cloud) to
   `Environment.SelfHosted(base = "https://vault.diegonmarcos.com")`.
3. `0003-vault-add-NOTICE-...patch` — adds `NOTICE.md` (GPL-3.0 election,
   trademark disclaimer, fork provenance). Upstream's `LICENSE.txt` is left
   untouched by all three patches.

All three apply cleanly (`git am`, zero fuzz) to a fresh checkout of the
pinned tag — verified in this session.

### Build status: UNVERIFIED

`com.bitwarden:sdk-android` resolves from
`https://maven.pkg.github.com/bitwarden/sdk`, which returns **401 without an
authenticated GitHub token** — even for public artifacts. No token available
in this environment works there, so **the gradle build itself has not been
run/verified**. `settings.gradle.kts` reads the credential as:

```kotlin
password = userProperties["gitHubToken"] as String? ?: System.getenv("GITHUB_TOKEN")
```
(`username` is hardcoded to `""`). CI must export `GITHUB_TOKEN` from a
secret — documented as **`BITWARDEN_PACKAGES_TOKEN`** — before invoking
`build.sh build-fork vault` (a PAT/fine-grained token with `read:packages`
on `bitwarden/sdk`; the default per-run `GITHUB_TOKEN` GitHub Actions mints
is scoped to this repo only and cannot read a different owner's packages).
The first CI run with that secret set is the real test of `build-fork vault`.
