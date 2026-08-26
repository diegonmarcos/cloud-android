# Cloud Mail

**This is our code.** The gradle project lives directly here — `app/`,
`build.gradle`, `settings.gradle` at this directory's root, exactly like
`aa_cloud-superapp`, which is this repo's shape for an app we own. There is no
`upstream/` subdirectory, no patch series, no `git am` step. Edit the source in
place, commit, build.

- **App id**: `com.diegonmarcos.comms.mail`
- **Source tree**: this directory — `app/src/main/java/eu/faircode/email/`
- **Build**: `./build.sh build-fork mail`

## Provenance and licence

Derived from [FairEmail](https://github.com/M66B/FairEmail) by M66B, **GPL-3.0**,
originally taken at tag `1.2321`. That attribution is a licence obligation and
stays regardless of how much we diverge — the GPL headers in each source file are
the authoritative notice.

`build.json::forks.mail` still records `upstream_repo` + `pinned_tag`. That is
provenance and a re-vendor escape hatch, not a live build input: nothing clones
or patches during a normal build.

## How the build finds the source

`materialize-fork` returns early when the tracker directory exists without a
`.git` inside it — which is the case here — and `build-fork` compiles this
directory directly. The clone-at-pin + `git am` path further down the engine is
for the OTHER apps that still carry a patch series (`aa_cloud-chat`), not for
this one.

To re-vendor a newer release from the original project: bump `pinned_tag`, clear
this directory's source, run
`./build.sh materialize-fork mail` (that path clones fresh), then re-apply our
divergence by hand. Our changes are ordinary commits in this repo's history, so
`git log -- aa_cloud-mail` is the record of what we changed and why.

## Our divergence from upstream

The 63-patch series that used to live in `patches/` was applied into the source
on 2026-08-19 and deleted on 2026-08-26; the engine had already stopped reading
them, so they were a stale historical record that looked live. The tree was
hoisted out of `upstream/` to this directory on 2026-08-26.

`tools/dep-patches/` holds 15 patches the ORIGINAL project kept for its vendored
dependencies (Bugsnag, colorpicker, emoji…). Nothing in gradle reads them and
they are already applied to the in-tree copies; they live under `tools/` rather
than a top-level `patches/` so the engine's `patch_dir` can never mistake them
for a fork series. Broadly
what we added:

- JMAP account type and sync engine (`JmapService`, `JmapSync`, `FragmentJmapAccount`)
- Native RSS/Atom reader with feed folders and channel provisioning
- Import-Configs single-JSON provisioner
- Self-updater against GitHub Releases (`CommsUpdateWorker`) and an About tab
- Branding, always-pro, nav-drawer folder tree

Server side: Maddy serves IMAP (INBOX + the F* sender folders), Stalwart serves
JMAP (the full 1*-9* + A*-F* structure). They are deliberately separate stores;
see `cloud-infra/a_solutions/user-comm_tools-stalwart/build.json::l4_ports`.
