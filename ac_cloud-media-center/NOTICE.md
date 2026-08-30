# NOTICE — Cloud Media Center

Cloud Media Center began as a fork of **ReFra** (formerly Gallery)
<https://github.com/IacobIonut01/ReFra>, vendored at tag
`5.1.1-51101-nightly`, and is **no longer tracked against it**. The source in
this directory is maintained here; upstream is history, not a branch we rebase
onto.

## Licence

ReFra is licensed **Apache-2.0**, and this work remains under Apache-2.0. The
full licence text is in `LICENSE`, retained unmodified.

Apache-2.0 §4 requires that derivative works keep the licence, the copyright
notices, and a statement of changes. Owning the code does not change that:
`LICENSE` and `UPSTREAM-README.md` stay exactly as upstream published them,
and the derivation is recorded here rather than quietly dropped.

## Statement of changes

The package identity was renamed from upstream's to ours across the whole
tree — 863 source files, plus the Room schema directory (named after the
`@Database` FQN) and the ContentProvider authorities:

| | upstream | this work |
|---|---|---|
| package / namespace | `com.dot.gallery` | `com.diegonmarcos.mediacenter` |
| baseline-profile module | `com.dot.baselineprofile` | `com.diegonmarcos.mediacenter.baselineprofile` |
| staging provider authority | `com.dot.staging.*` | `com.diegonmarcos.mediacenter.staging.*` |
| applicationId | `com.dot.gallery` | `com.diegonmarcos.mediacenter` |

The provider-authority rename is not cosmetic: authorities are a device-global
namespace, so while ours were still declared under `com.dot.gallery.*` this
app could not be installed alongside genuine ReFra without
`INSTALL_FAILED_CONFLICTING_PROVIDER`.

Beyond the rename, this fork adds the cloud provider layer under
`com.diegonmarcos.mediacenter.cloud` — WebDAV / Nextcloud / ownCloud / SMB /
NFS / Immich clients, the Room schema behind them, and the sync engine — none
of which exists upstream.

## Relationship to upstream

Not endorsed by, affiliated with, or supported by the ReFra authors. Report
issues with this build here, never to upstream.
