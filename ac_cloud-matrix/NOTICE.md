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

**Known remaining item:** the launcher artwork still comes from the upstream
`appicon/element/` module and has NOT been replaced. Until it is, this build
carries Element's mark under a different name, which is the wrong way round
for a trademark. Replacing it is tracked as the next step of this rebrand;
`ac_cloud-vault/patches/0001` is the worked example of doing the same for
Bitwarden's shield.

## Relationship to upstream

This fork is not endorsed by, affiliated with, or supported by Element
Creations Ltd. / New Vector Ltd. Please report issues with this build here,
never to upstream.
