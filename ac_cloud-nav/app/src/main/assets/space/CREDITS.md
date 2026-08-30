# Space View data credits

This folder was renamed from `galaxy/` to `space/` when the screen was
refocused onto the Solar System (see `space_view.html`'s top-of-file
comment). `hyg_stars.bin`/`hyg_named.json` (the earlier interstellar
star-field layer) are kept here but NOT currently wired into
`space_view.html` -- left in case that layer is wanted back.

`hyg_stars.bin` and `hyg_named.json` are derived from the **HYG Stellar
Database v4.4** (astronexus, https://codeberg.org/astronexus/hyg),
which merges the Hipparcos, Yale Bright Star, and Gliese (nearby star)
catalogs into a single table with real parallax-derived 3D positions
(x/y/z in parsecs, Sol at the origin).

License: **CC BY-SA 4.0** (http://creativecommons.org/licenses/by-sa/4.0/).
This derived data (a binary re-encoding of the x/y/z/mag/ci columns for
all 119,614 stars, plus a JSON subset of the 549 stars with real proper
names) is redistributed under the same license, attribution above.

Not derived from Stardroid (GPLv3) or any other GPL-licensed source.

## Format

`hyg_stars.bin`: little-endian float32, 5 values per star, no header:
`x, y, z` (parsecs, HYG's own heliocentric equatorial coordinate frame),
`mag` (apparent magnitude), `ci` (B-V color index; `99.0` = unknown).

`hyg_named.json`: array of `{name, con, x, y, z, mag, dist, spect}` for
the 549 stars carrying a real proper name in the source catalog.

## three.js

`three.module.min.js` and `OrbitControls.js` are three.js v0.185.1
(https://threejs.org), MIT License, bundled locally so the render engine
loads with no network (constellation/planet data is also fully local;
this screen needs no network at all, unlike terrain_map.html which
depends on live map/imagery tiles).

Constellation lines and planetary Keplerian elements reuse the already-
bundled, already-credited `../celestial/` dataset (see
`../celestial/CREDITS.md`). Moon orbital periods (semi-major axis not
used -- see space_view.html's scale disclosure) are real NASA/JPL-
sourced constants, cross-checked against independent sources, not from
that dataset.
