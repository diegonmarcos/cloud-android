# libs/firewall/firestack — vendored engine source AND its build output

This one directory holds two things, deliberately:

1. **The vendored source** of [`celzero/firestack`](https://github.com/celzero/firestack)
   (MPL-2.0, Go) — the gVisor-netstack + WireGuard-proxy engine behind
   RethinkDNS. Committed, owned, not cloned at build time. Only what upstream
   tracks is here (224 files); upstream's own `bin/` is build output and is not
   tracked there either.

2. **`firestack.aar`** — the gomobile build output, produced by
   `./build.sh firestack` and **gitignored**. Same doctrine as `libs/net`'s
   `libwg-go.so`: build artifacts are never committed.

They share a folder because `lib-apks/settings.gradle` resolves its `flatDir`
to `<scan-root>/firewall/firestack`, and that path has to be where the aar
lands. Putting the source somewhere else would mean two paths to keep in step,
which is the drift this consolidation removed.

The tree carries **no `.git`** — that is the marker for "vendored, do not
clone", the same one the app forks use. `build.sh` tests for `go.mod`, not for
`.git`; testing for `.git` would re-clone upstream over the committed source on
every build.

This is **not** a gradle module (absent from `build.json::modules`). The aar is
self-contained (gomobile bundles all Go deps), so Phase 3 consumes it via the
central `flatDir` plus a plain aar dependency — no wrapper module needed.

The Kotlin that uses it is staged next door in `../phase3-firestack/`, outside
the sourceset, because it only compiles once the aar exists.
