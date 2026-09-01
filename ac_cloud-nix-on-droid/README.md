# Cloud Nix-on-Droid

Our build of [Nix-on-Droid](https://github.com/nix-community/nix-on-droid-app), vendored into the fleet.

## Why it is here

The watchdog app needed to run a command in a terminal that belongs to a
different app, and Android gives you almost nothing for that: `RUN_COMMAND`
fires and forgets, and the interesting output is on the far side of a sandbox.
The workaround was ssh to loopback, which meant depending on someone else's
sshd being installed, running, on the expected port and holding our key — four
things that can each be false on a phone, and were.

Owning the terminal replaces all four with one: a bound service, guarded by a
signature-level permission, that only APKs signed with the constellation key
can call. This is the DECLARATIVE env — the machine is described in Nix and rebuilt from it; commands are run in the Termux fork beside it.

## What we changed

Nothing yet beyond vendoring. Divergence from upstream belongs in this section
as it lands, so the cost of the fork stays visible.

## applicationId

Left at upstream's `com.termux.nix`, deliberately. Termux compiles the prefix
`/data/data/com.termux/files/usr` into every binary it ships, and Nix-on-Droid
does the same with `com.termux.nix` for its Nix store, so renaming the package
orphans the whole bootstrap and every package in it.

We own the source and sign it with our own key; we do not own the namespace.
The consequence is accepted rather than worked around: this build REPLACES an
installed upstream Nix-on-Droid rather than sitting beside it — same id, different
signature — so the upstream one has to be uninstalled first.

## Licence and provenance

Nix-on-Droid, the Nix-on-Droid developers, GPL-3.0. Vendored at `e87b609`. The upstream licence is kept
verbatim in `LICENSE.md`; see `ATTRIBUTION.md`. This is a derivative work and
stays GPL-3.0.
