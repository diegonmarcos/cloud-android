# Cloud Mail

**This is our code.** Not a fork with a patch series on top — a single, directly
editable Java source tree at `upstream/`. Edit it like any other code in this
repo: change the file, commit, build. There is no patch to refresh, no series to
rebase, no `git am` step that can conflict.

- **App id**: `com.diegonmarcos.comms.mail`
- **Source tree**: `upstream/` — 3,178 tracked files, no `.git` inside it
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
`.git` inside it — which is the case here — and `build-fork` compiles
`upstream/` directly. The clone-at-pin + `git am` path further down the engine is
for the OTHER apps that still carry a patch series (`aa_cloud-chat`), not for
this one.

To re-vendor a newer upstream release: bump `pinned_tag`, delete `upstream/`, run
`./build.sh materialize-fork mail` (that path clones fresh), then re-apply our
divergence by hand. Our changes are ordinary commits in this repo's history, so
`git log -- aa_cloud-mail/upstream` is the record of what we changed and why.

## Our divergence from upstream

The 63-patch series that used to live in `patches/` was applied into `upstream/`
on 2026-08-19 and the files deleted on 2026-08-26; the engine had already stopped
reading them, so they were a stale historical record that looked live. Broadly
what we added:

- JMAP account type and sync engine (`JmapService`, `JmapSync`, `FragmentJmapAccount`)
- Native RSS/Atom reader with feed folders and channel provisioning
- Import-Configs single-JSON provisioner
- Self-updater against GitHub Releases (`CommsUpdateWorker`) and an About tab
- Branding, always-pro, nav-drawer folder tree

Server side: Maddy serves IMAP (INBOX + the F* sender folders), Stalwart serves
JMAP (the full 1*-9* + A*-F* structure). They are deliberately separate stores;
see `cloud-infra/a_solutions/user-comm_tools-stalwart/build.json::l4_ports`.
