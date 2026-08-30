# NOTICE — Cloud Matrix

Cloud Matrix is a fork of **Element X Android**
(<https://github.com/element-hq/element-x-android>), vendored at tag
`v26.06.2`.

## Licence

Upstream is dual-licensed `AGPL-3.0-only OR LicenseRef-Element-Commercial`.
This fork is distributed under **AGPL-3.0-only**; the commercial option is not
elected and is not available through this distribution. The full licence text
is in the repository root as shipped by upstream.

As an AGPL work, the corresponding source for any deployed build is this
directory, which is committed in full rather than fetched at build time.

## Trademarks

"Element", "Element X" and the Element logo are trademarks of Element
Creations Ltd. / New Vector Ltd. They are **not** licensed by the AGPL and are
**not** claimed here.

Accordingly this fork ships under its own name and package identity:

| | upstream | this fork |
|---|---|---|
| application name | Element X | Cloud Matrix |
| applicationId | io.element.android.x | com.diegonmarcos.comms.matrix |

The launcher artwork is original too. Element's mark in `appicon/element/`
— 20 per-density `.webp` rasters plus the Play-Store `.png` — has been
deleted, not merely renamed around, and replaced with an original vector: three
nodes joined into a triangle, a mesh, for a federated Matrix client. No Element
artwork is reused.

`appicon/enterprise/` still holds upstream's enterprise artwork. It is never
built into this APK — `app/build.gradle.kts` selects `appicon.enterprise` only
when `isEnterpriseBuild`, and the FOSS/fdroid flavour we ship takes
`appicon.element`. It is kept so that branch still compiles.

## Relationship to upstream

This fork is not endorsed by, affiliated with, or supported by Element
Creations Ltd. / New Vector Ltd. Please report issues with this build here,
never to upstream.
