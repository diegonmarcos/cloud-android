# ea_upstreams-sources/

Reference index of every third-party Android app whose source we
cherry-pick (or plan to cherry-pick) into `ea_cloud-superapp/libs/`.

The actual upstream working copies are NOT tracked in this repo —
they live next to `ea_cloud-superapp/` as gitignored siblings
(`ea_<role>-<upstream>/`). This folder is the documented inventory
so any clone of `unix/` can re-clone them deterministically.

## Convention

Each subdirectory matches one upstream and one `libs/<role>` target:

| Role | Subdir | Upstream | libs/&lt;role&gt; status |
|---|---|---|---|
| cal | [cal-davx5](cal-davx5/README.md) | bitfireAT/davx5-ose | empty stub |
| feed | [feed-feeder](feed-feeder/README.md) | spacecowboy/Feeder | empty stub |
| mail | [mail-fairmail](mail-fairmail/README.md) | M66B/FairEmail | active (10 files) |
| net | [net-wireguard](net-wireguard/README.md) | WireGuard/wireguard-android | active (20 files) |
| vault | [vault-keepassdx](vault-keepassdx/README.md) | Kunzisoft/KeePassDX | empty stub |

The local clone path for each is `ea_<role>-<upstream>/` (sibling of
`ea_cloud-superapp/`), e.g. the cal-davx5 README documents the
`ea_cal-davx5/` directory.

## Why no submodules?

These upstreams are large (~300 MiB combined). Tracking them — even
as submodules — adds friction for casual contributors. The cherry-
pick targets in `libs/` are the only code we actually ship, and they
are tracked. The upstreams stay as ad-hoc clones for one-time
extraction.
