# Galaxy view data credits

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
loads with no network (star/constellation data is also fully local; this
screen needs no network at all, unlike terrain_map.html/milkyway_map.html
which depend on live map/imagery tiles).

Constellation lines reuse the already-bundled, already-credited
`../celestial/constellations.lines.json` (see `../celestial/CREDITS.md`).
