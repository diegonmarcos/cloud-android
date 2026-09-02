# libs:repos

Read-only, history-free snapshots of the cloud and front repos, so this machine
can read any of them without cloning them, without them going stale, and
without any of them ever being dirty.

```sh
./sync.sh                 # sync everything due
./sync.sh --list          # what is indexed, and how current
./sync.sh cloud-infra     # just one
./sync.sh --force         # re-download even if the sha matches
./sync.sh --refresh-list  # regenerate repos.json from the symlink farms
```

## Not a clone

`sync.sh` downloads the tip **tarball** and extracts it. There is no `.git`
directory anywhere under this folder.

That is the whole design. "Never dirty" is not a rule anyone has to keep — there
is no repository to commit from, no branch to drift, nothing to reset. Files are
`chmod a-w` on top, so an accidental editor save fails loudly rather than
silently creating a change that can never be pushed anywhere.

## Never behind, cheaply

Each snapshot records the commit it came from in `.head`. A sync asks GitHub for
the branch head — one API call — and re-downloads only when it differs. An
unchanged repo costs one request and no bytes.

The download lands in `<name>.new` and is swapped in atomically, so an
interrupted sync leaves the previous snapshot intact rather than a half-written
tree.

## The list is not invented here

`repos.json` is the union of `~/git/cloud/repos.json` and
`~/git/front/repos.json` — the two symlink farms that already index this
account. Regenerate with `--refresh-list`.

This matters more than it looks: in those registries `name` is the **local
directory** and `url` is the **GitHub repo**, and they legitimately differ —
`front-diegonmarcos` is `diegonmarcos.github.io`, `front` is `ffront`. Deriving
the list from a GitHub name filter silently misses repos, which is exactly the
mistake this file exists to avoid repeating.

## What is excluded, and why

| excluded | reason |
|---|---|
| `cloud-u-android` | this repo — a snapshot of ourselves inside ourselves |
| `cloud-vault` | **secrets.** This is a public repo; a never-cleaned directory is the worst home for credentials |
| `front-assets-cdn` | 1.4 GB of binary assets, no source |
| `cloud`, `front` | the symlink farms — snapshotting links says nothing |

`private: true` entries are indexed but **not synced** unless `include_private`
is set: they would sit as plaintext source inside a public repo's working tree.

## Size

Public set is ~1.0 GB, dominated by `front-diegonmarcos` at 738 MB — of which
~510 MB is media (`.jpg` 275, `.png` 150, `.mp4` 47). Adding those extensions to
`exclude_globs` in `repos.json` halves the mirror and drops no source.

## Why there is no build.gradle

There deliberately is none. `ab_cloud-libs-shared/lib-apks` builds **one APK per
module** and finds modules by looking for a `build.gradle` in each child of
`libs/`. Adding one here would produce an APK containing other repositories'
source. This is a data directory that happens to live under `libs/`.
