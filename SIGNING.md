# Android signing — ONE shared constellation key

**Every** Android app in the constellation signs with **one and only one** key.
No exceptions, no per-app keys, no random fallback — ever.

## The one key

| | |
|---|---|
| **Keystore** | `vault/A0_keys/providers/android/release.jks` (PKCS12, RSA-4096) |
| **Passwords** | `vault/A0_keys/providers/android/signing.secrets.yaml` (sops/age) — keys: `keystore_password`, `key_password`, `key_alias` |
| **Alias** | `cloud-constellation` |
| **Subject** | `CN=Diego Coelho Marcos, OU=Cloud SuperApp, O=diegonmarcos.com, L=Berlin, ST=Berlin, C=DE` |
| **SAN** | `email:me@diegonmarcos.com`, `URI:https://linktree.diegonmarcos.com` |
| **SHA-256** | `50:7E:56:A3:5B:0E:0D:7E:0A:CE:55:16:F4:94:96:E6:2F:ED:A7:21:ED:6C:17:6D:DF:B3:34:12:9C:EE:18:99` |
| **Validity** | until 2056 (30y) |

> SuperApp is the top of the constellation hierarchy, so the shared key carries
> `OU=Cloud SuperApp`. Prior keys (`C0:F9:4B:17` OU=Cloud Constellation,
> `CB:02:83:34` OU=Cloud SuperApp, `34:AC:80` OU=Cloud-Comms) are **retired**.

The raw keystore + passwords live **only in the private `vault` repo**. Public
repos reference the vault *paths* (in each app's `build.json::signing`), never the
material.

## Why one key (do not "fix" this to per-app keys)

The constellation apps talk over a `protectionLevel=signature` IPC permission, and
the in-app fleet updater installs updates across apps. Android refuses to update an
installed app — or grant signature-IPC — across **different** signing keys. So all
of these MUST share the single key above:

Concretely: **every** `ac_cloud-*` app whose `build.json` declares
`signing.vault_keystore` — 17 of them as of 2026-08-26, including their forks
(mail/FairEmail, dialer/Fossify, ide, media-center, …). The list is not
maintained here; it *is* the set of build.json files that declare the key:

```sh
rg -l vault_keystore --glob 'a[abc]_*/build.json'
```

## How it's wired (declarative)

- `build.json::signing.vault_keystore` / `.vault_secrets` → the vault paths above (identical in all 4 apps).
- `build.sh::_resolve_signing` → resolves the key and exports `ANDROID_KEYSTORE_FILE/PASSWORD` + `ANDROID_KEY_ALIAS/PASSWORD`. It accepts a CI-pre-set keystore env **or** a vault checkout (`VAULT_DIR` + sops). If the key cannot be resolved it **fails loud (`exit 1`)** — it never generates or substitutes another key.
- gradle `signingConfig` reads only those env vars and **throws** if `ANDROID_KEYSTORE_FILE` is absent (no debug-sign, no legacy keystore).
- forks: comms writes `keystore.properties` from the resolved key; ide imports the shared keypair into the fork keystore (same signature). No `keytool -genkeypair`, anywhere.

## CI (builds are GHA-only)

Each `ship-cloud-*.yml` checks out the private vault and decrypts the key:

- secret **`ANDROID_SIGNING_VAULT_TOKEN`** — fine-grained PAT, read access to `diegonmarcos/cloud-vault`.
- secret **`SOPS_AGE_KEY`** — age key to decrypt `signing.secrets.yaml`.

No keystore is cached or generated in CI. Missing either secret → the build fails
(by design) rather than signing with a wrong key.

> **`ANDROID_SIGNING_VAULT_TOKEN` on cloud-u-android currently holds a
> broad-scope token, not a scoped PAT (2026-08-26).** cloud-vault is private
> and there is no API for minting a fine-grained PAT, so the value set here is
> the account's `gh` CLI token, which carries `repo`, `workflow`, `admin:org`,
> `admin:enterprise` and `delete_repo` among others. Every workflow run in this
> repo can therefore do far more than read one vault. This was a deliberate,
> informed choice to unblock CI after the android split, **not** the intended
> end state.
>
> Replace it when convenient — no code change required, the workflows already
> read the right secret name:
>
> 1. Mint at `github.com/settings/personal-access-tokens/new` — owner
>    `diegonmarcos`, repository access **only** `diegonmarcos/cloud-vault`,
>    permission **Contents: Read**.
> 2. `gh secret set ANDROID_SIGNING_VAULT_TOKEN -R diegonmarcos/cloud-u-android`
> 3. Re-run any ship workflow to confirm.
>
> A read-only **deploy key** on cloud-vault is the other correct option, but it
> needs `actions/checkout` switched from `token:` to `ssh-key:` in all 18
> workflow sources, and would leave cloud-u-android authenticating differently
> from cloud-infra-desktop.

> **GitHub secrets do not move with workflows.** The 2026-08-25 migration of the
> `ea_*` app trees out of `cloud-infra-desktop` carried the `ship-cloud-*.yml` workflows
> but *not* these two secrets, so every ship run failed with
> `Input required and not supplied: token` until they were re-set on
> `diegonmarcos/cloud-u-android`. Secrets are write-only — they cannot be copied
> between repos, only re-supplied. `cloud-infra-desktop` keeps its own copies because
> `ship-cloud-infra-desktop-termux-boot.yml` still signs with the same key.

## Re-keying cost

Whenever the canonical key changes, **every** installed app must be uninstalled +
reinstalled once (Android can't cross-key update). After that, all apps share the
one key and update cleanly. This is the *only* situation that requires a device
reinstall — keep the key stable to avoid it.

## GHCR package linkage (2026-08-26)

Every fleet package is still bound to `diegonmarcos/cloud-infra-desktop`, the repo that
first pushed it. A workflow's `GITHUB_TOKEN` only grants packages bound to its
own repo, so pushes from cloud-u-android fail with
`denied: permission_denied: write_package` unless a packages-scoped token is
used. The `oras login` step therefore reads
`${{ secrets.GHCR_TOKEN || secrets.GITHUB_TOKEN }}`.

Pushes now set `org.opencontainers.image.source`, which is what GHCR reads to
link a package — but **only at package creation**. Verified: the manifest
carries the cloud-u-android source annotation and the package is still linked to
cloud-infra-desktop. So the annotation is correct for new packages and cannot fix the
existing ones; re-linking those has no REST API for a user account and is
per-package in the web UI. `GHCR_TOKEN` stays required until then.

Check with `gh api user/packages/container/<name> --jq .repository.full_name`.
