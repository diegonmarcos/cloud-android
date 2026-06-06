# vault-keepassdx — KeePassDX

| Field | Value |
|---|---|
| Upstream | https://github.com/Kunzisoft/KeePassDX.git |
| License | GPL-3.0-or-later |
| Local clone path | `../../ea_vault-keepassdx/` |
| Last pinned commit | `8bb8755` (2026-05-28) |
| Cherry-pick target | `ea_cloud-superapp/libs/vault/` |
| Status | Empty stub — wired but no code yet |

## Re-clone

```bash
git clone https://github.com/Kunzisoft/KeePassDX.git \
  ~/git/unix/ea_vault-keepassdx
git -C ~/git/unix/ea_vault-keepassdx checkout 8bb8755
```

## What we'll cherry-pick

The KDBX file format reader/writer (`com.kunzisoft.keepass.database.*`)
+ crypto primitives. The vault UI surfaces in the superapp will be
re-implemented; the upstream's UI ships full activities that don't
fit the Fragment-host pattern.
