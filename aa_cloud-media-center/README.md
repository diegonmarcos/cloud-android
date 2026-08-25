# Cloud Media Center

Photo/video gallery app. `applicationId com.diegonmarcos.mediacenter`, forked from
[IacobIonut01/ReFra](https://github.com/IacobIonut01/ReFra) (ex-`IacobIonut01/Gallery`,
upstream package `com.dot.gallery`, Kotlin + Jetpack Compose, minSdk 29).
Pinned at upstream tag **`5.1.1-51101-nightly`** (newest tag as of 2026-07-26 — see
`build.json::fork.pinned_tag`; upstream ships nightlies as its release channel and
has no recent stable tag — `4.3.0` is the last plain-semver one; review the pin
whenever upstream cuts an actual stable release). Tracker clone lives at
(gitignored, materialized at build time) `../ea_upstreams-sources/media-refra`.

**Native build dependency:** upstream is not pure Kotlin/Compose — it has
`externalNativeBuild` + CMake with JNI over libheif/libde265. NDK
`29.0.14033849` and CMake `3.31.6` (see `build.json::toolchain`) are REQUIRED
to build, not optional.

## License: GPL-3.0 (not upstream's Apache-2.0)

Upstream ReFra is Apache-2.0. **This fork is GPL-3.0 for the whole app**, because
it statically links `com.github.teamnewpipe:NewPipeExtractor` (GPL-3.0, via
JitPack) for the YouTube media source. Combining a GPL-3.0 dependency into an
app requires the resulting APK to be distributed under GPL-3.0 terms — this is
a decided relicense, not an oversight. See `build.json::license` for the
machine-readable record; it must also be surfaced in the app's about/licenses
screen once implemented.

## Architecture: profiles, not hardcoded sources

Every media source ("profile") presents the same Album/MediaItem model to the
UI. The ONLY place profiles are declared is `data/media-profiles.json` —
Kotlin (once patched in) iterates it; a hardcoded source list in Kotlin is
forbidden. Three source types:

- **`local`** — MediaStore, upstream code path unchanged.
- **`photoprism`** — WebDAV against the already-deployed `user-media_photoprism`
  service (`/originals/` to read, `/import/` to write/auto-index). Used for
  both the `personal-media` profile and the `instagram` profile. Being moved
  to `wg_only: true` (pending verification of Authelia bypass behaviour — see
  `data/media-profiles.json::profiles[personal-media]._doc_wg_only`).
- **`newpipe`** — NewPipeExtractor, YouTube only.

**Instagram is not an app-level source.** It has no SDK/API/session-cookie
integration in this app at all. It is mirrored server-side by a `gallery-dl`
job (own account's public posts only, no session cookie) into PhotoPrism's
import folder, so from the app's point of view it is just another
`photoprism` profile pointed at a sub-path. See
`data/media-profiles.json::profiles[instagram]._doc`.

## Build

```
nix develop        # devShell: gradle/AGP/Android SDK/JDK 17 pinned in flake.nix
```

**There is no `build.sh` yet.** See `## OPEN: engine choice` below — do not
invent one; resolve this before the first real build.

## `## OPEN: engine choice`

Two candidate engines were evaluated for `build.sh`. Neither could be used
as-is, and per FIRE rule 5/6 a hardcoded copy-paste was rejected in favor of
naming the actual blocker.

**`ea_cloud-mail/build.sh` is a symlink** to
`~/git/cloud-unix/1_cicd/src/scripts/cloud-comms-fork-engine.sh`. That engine
is genuinely comms-coupled, not a generic single-fork engine — evidence:

- `cloud-comms-fork-engine.sh:239` / `:236` (`step_build`) —
  `in_nix gradle :hub:assembleDebug` and
  `cp "$SCRIPT_DIR/hub/build/outputs/apk/debug/hub-debug.apk" "$out"` hardcode
  a `:hub:` in-tree gradle module. Cloud Media Center has no hub module — the
  fork IS the whole app (single-fork model, see `build.json::fork._doc`).
- `cloud-comms-fork-engine.sh:260` (`step_dev`) hardcodes the launch
  component `com.diegonmarcos.comms.MainActivity`.
- `cloud-comms-fork-engine.sh:67-131` (`step_bundle_forks`) writes embedded
  fork APKs into `hub/src/main/assets/forks/` — the hub-bundles-forks
  installer model, which does not apply to a standalone app.
- `cloud-comms-fork-engine.sh:279-310` (`step_verify_contract`) hardcodes
  `contract/comms-ipc-v1.json` / `comms-ipc-v1.schema.json` — an AIDL/IPC
  contract this app has no reason to have.
- The dispatch table (`cloud-comms-fork-engine.sh:759-782`) wires every
  top-level command (`build`, `release`, `dev`, `ship`) through the hub-coupled
  steps above; there is no "fork-only, no hub" invocation path today.

The `build-fork <key>` / `materialize-fork <key>` / `publish-fork <key>` /
`gh-release-fork <key>` steps (lines 312-517, 737-757) ARE already generic and
data-driven off `build.json::forks.<key>` — those alone would work fine for a
single-fork app if a bare, hub-less `build`/`release`/`ship` existed. The
`_resolve_signing` / `_enforce_signature` / `_assert_apk_identity` machinery
(lines 133-229) is fully generic and reusable as-is.

**`ea_cloud-ide/build.sh`** is a standalone ~22K non-symlinked engine, but it
is built around the "thin original hub + 3 embedded forks" model too (its
`step_bundle_forks` also targets `hub/src/main/assets/forks/`) — copying it
verbatim would carry the same hub assumption this app doesn't have.

**Two options, unresolved:**

1. Generalize `cloud-comms-fork-engine.sh` into a neutral
   `android-fork-engine.sh`: extract a hub-less `build`/`release`/`ship` path
   that runs `build-fork` directly against a single implicit fork key (e.g.
   sourced from `build.json::fork` singular, not `build.json::forks` map),
   and drop the `verify-contract` step for apps with no `ipc` block. This is
   the path implied by the README task brief's "later be renamed to a neutral
   `android-fork-engine.sh`" note.
2. Follow the `ea_cloud-ide` standalone pattern but strip the hub/bundle
   layer entirely, writing a new minimal single-fork engine from scratch
   (still symlink-shareable if a second single-fork app appears later).

Until one of these is chosen and implemented, `./build.sh materialize-fork`
etc. do not exist for this app — only the devShell (`nix develop`) is usable.

## Not yet implemented

- **The WebDAV upload spike** — nobody has tested whether ReFra's existing
  ownCloud/WebDAV provider can talk to PhotoPrism at all, and whether it can
  UPLOAD or is read-only. This is the first thing to do, before any Kotlin —
  a ~20-minute manual test with the stock F-Droid ReFra APK against
  `https://photos.diegonmarcos.com`. It decides whether backup is a config
  change or a real patch.
- **Kotlin patch series** — `patches/` is empty. The tracker checkout at the
  pinned tag (`5.1.1-51101-nightly`) is a valid scaffolding state (matches
  `cloud-comms-fork-engine.sh`'s "no patches yet" behavior), but no profile
  picker, PhotoPrism WebDAV source, or NewPipeExtractor integration exists
  yet.
- **Media sync upload — BLOCKED on the cloud service, not just the app.**
  `user-media_photoprism` currently mounts `originals` read-only and has no
  `import` volume at all (see `a0_tasks/PLAN_cloud-media-center.md` for the
  verified blockers and the wg_only migration in flight). No backup code can
  be written until that service is changed.
- **`build.sh` engine choice** — see `## OPEN: engine choice` above.
- **PhotoPrism WebDAV app password** — does not exist yet
  (`photoprism auth add --scope webdav`); its sops key name is unassigned,
  hence `auth_secret_ref: "PLACEHOLDER"` in `data/media-profiles.json`.

## Patches directory convention

`patches/` holds a git-am-format patch series applied against the pinned
tracker checkout (`../ea_upstreams-sources/media-refra` at tag
`build.json::fork.pinned_tag`), in lexical apply order. Naming:

```
NNNN-media-<slug>.patch
```

e.g. `0001-media-add-manifest-source.patch`, `0002-media-add-newpipe-source.patch`.
Currently empty — see `## Not yet implemented`.
