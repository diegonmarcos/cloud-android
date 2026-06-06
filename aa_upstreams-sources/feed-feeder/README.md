# feed-feeder — Feeder (RSS reader)

| Field | Value |
|---|---|
| Upstream | https://github.com/spacecowboy/Feeder.git |
| License | GPL-3.0-or-later |
| Local clone path | `../../ea_feed-feeder/` |
| Last pinned commit | `a931c0b` (2026-05-21) |
| Cherry-pick target | `ea_cloud-superapp/libs/feed/` |
| Status | Empty stub — wired but no code yet |

## Re-clone

```bash
git clone https://github.com/spacecowboy/Feeder.git \
  ~/git/unix/ea_feed-feeder
git -C ~/git/unix/ea_feed-feeder checkout a931c0b
```

## What we'll cherry-pick

The Atom/RSS parser + sync scheduler — `com.nononsenseapps.feeder.db`
+ network/parsers. RssFeedFragment in the superapp consumes its
output via a thin Kotlin wrapper.
