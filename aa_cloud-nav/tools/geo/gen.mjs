#!/usr/bin/env node
/*
 * Geo asset generator for Explored's map layers (Approach A: MapLibre FillLayer).
 *
 * Produces compact, simplified GeoJSON bundled into the app at
 *   libs/maps/src/main/assets/geo/*.geojson
 * from AUTHORITATIVE, reproducible sources — never hand-drawn:
 *
 *   countries.geojson   Natural Earth admin_0 (10m — real coastlines), lightly
 *                       simplified. props: NAME, ISO2, CONTINENT
 *   continents.geojson  countries dissolved by CONTINENT.        props: CONTINENT
 *   states.geojson      admin-1 (Brazil states, Spain autonomous regions…),
 *                       Natural Earth 10m simplified to ~2km. props: name, ISO2, admin
 *   regions.geojson     Political-Cultural Regions — countries dissolved by the
 *                       civilization SUBREGION taxonomy extracted from the front
 *                       app (civilizations.json). props: subregion, region, civ
 *   nomad.geojson       NomadMania regions — convex hull of the traveller's own
 *                       trip points per nomadRegion (from front travel-data.json).
 *   cities.geojson      distinct visited city points (from travel-data.json).
 *
 * Run:  cd tools/geo && node gen.mjs
 * (needs network on first run to cache Natural Earth into ./.cache; mapshaper via npx.)
 *
 * The OUTPUT geojson is the committed source of truth for the app; this script +
 * civilizations.json + the pinned NE URLs make it reproducible.
 */
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const DIR = dirname(fileURLToPath(import.meta.url));
const CACHE = join(DIR, '.cache');
const OUT = join(DIR, '..', '..', 'libs', 'maps', 'src', 'main', 'assets', 'geo');
mkdirSync(CACHE, { recursive: true });
mkdirSync(OUT, { recursive: true });

const FRONT_TRAVEL = '/home/diego/git/front/b-MyData/mymaps-mytrips/public/travel-data.json';
const NE = 'https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson';

function ms(args) {
  const r = spawnSync('npx', ['--yes', 'mapshaper', ...args], { stdio: 'inherit' });
  if (r.status !== 0) throw new Error('mapshaper failed: ' + args.join(' '));
}
async function cache(url, name) {
  const p = join(CACHE, name);
  if (!existsSync(p)) {
    process.stderr.write(`fetch ${url}\n`);
    const r = await fetch(url);
    if (!r.ok) throw new Error(`fetch ${url} -> ${r.status}`);
    writeFileSync(p, Buffer.from(await r.arrayBuffer()));
  }
  return p;
}

// ── sources ──────────────────────────────────────────────────────────────
// 10m = the DETAILED Natural Earth scale — real country coastlines, not the
// blocky 110m outline. Simplified only lightly (retain enough vertices to keep
// shapes recognisably exact) with keep-shapes so small islands survive.
const admin0 = await cache(`${NE}/ne_10m_admin_0_countries.geojson`, 'admin0_10m.geojson');
const civ = JSON.parse(readFileSync(join(DIR, 'civilizations.json'), 'utf8'));
const travel = JSON.parse(readFileSync(FRONT_TRAVEL, 'utf8'));

// ── ISO2 → {region, civ, subregion} table from the civilization tree ───────
const rows = [['ISO2', 'region', 'civ', 'subregion']];
for (const r of civ.tree)
  for (const c of r.civilizations)
    for (const s of c.subregions)
      for (const iso of s.countries) rows.push([iso, r.region, c.name, s.name]);
const isoCsv = join(CACHE, 'iso2_regions.csv');
writeFileSync(isoCsv, rows.map((r) => r.map((v) => JSON.stringify(v)).join(',')).join('\n'));

// Compact subregion → member-ISO2 map (runtime "is this region visited?" lookup).
const members = [];
for (const r of civ.tree)
  for (const c of r.civilizations)
    for (const s of c.subregions)
      members.push({ subregion: s.name, region: r.region, civ: c.name, countries: s.countries });
writeFileSync(join(OUT, 'region-members.json'), JSON.stringify(members));

// ── 1. countries.geojson ───────────────────────────────────────────────────
const countries = join(OUT, 'countries.geojson');
// Countries at TRUE 100m coastline resolution (interval=100). precision=0.001
// (~110m) matches that — finer decimals just bloat the file without adding
// visible detail at 100m.
ms([admin0, '-simplify', 'interval=100', 'visvalingam', 'weighted', 'keep-shapes',
  '-each', 'ISO2 = (ISO_A2 == "-99" ? ISO_A2_EH : ISO_A2)',
  '-filter', 'CONTINENT != "Antarctica" && CONTINENT != "Seven seas (open ocean)"',
  '-filter-fields', 'NAME,ISO2,CONTINENT',
  '-o', 'format=geojson', 'precision=0.001', countries]);

// ── 2. continents.geojson ──────────────────────────────────────────────────
// Continents/regions are huge areas — 100m detail is invisible and wasteful, so
// dissolve then simplify to ~1km. Keeps the bundle small.
ms([countries, '-dissolve', 'CONTINENT', '-simplify', 'interval=1000', 'keep-shapes',
  '-o', 'precision=0.001', join(OUT, 'continents.geojson')]);

// ── 3. regions.geojson (Political-Cultural Regions) ────────────────────────
ms([countries,
  '-join', isoCsv, 'keys=ISO2,ISO2', 'string-fields=ISO2', 'fields=region,civ,subregion',
  '-filter', 'subregion != null',
  '-dissolve', 'subregion', 'copy-fields=region,civ',
  '-simplify', 'interval=1000', 'keep-shapes',
  '-o', 'format=geojson', 'precision=0.001', join(OUT, 'regions.geojson')]);

// ── 3b. states.geojson — admin-1 (Brazil states, Spain autonomous regions…) ─
// The first subdivision below country. World-wide but simplified hard (~2km) —
// state borders don't need fine detail for a choropleth. props: name, ISO2
// (country), admin (country name).
const admin1 = await cache(`${NE}/ne_10m_admin_1_states_provinces.geojson`, 'admin1_10m.geojson');
ms([admin1, '-simplify', 'interval=2000', 'visvalingam', 'weighted', 'keep-shapes',
  '-each', 'ISO2 = iso_a2',
  '-filter-fields', 'name,ISO2,admin',
  '-o', 'format=geojson', 'precision=0.01', join(OUT, 'states.geojson')]);

// ── 4. nomad.geojson — convex hull per nomadRegion (traveller's own points) ─
function hull(points) {
  // Andrew's monotone chain (lng,lat). Needs >=3 distinct points for a polygon.
  const pts = [...new Map(points.map((p) => [p.join(','), p])).values()].sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  if (pts.length < 3) return null;
  const cross = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
  const lower = [];
  for (const p of pts) { while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], p) <= 0) lower.pop(); lower.push(p); }
  const upper = [];
  for (let i = pts.length - 1; i >= 0; i--) { const p = pts[i]; while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], p) <= 0) upper.pop(); upper.push(p); }
  const ring = lower.slice(0, -1).concat(upper.slice(0, -1));
  if (ring.length < 3) return null;
  ring.push(ring[0]);
  return ring;
}
const byNomad = new Map();
for (const t of travel.trips) {
  if (!t.nomadRegion || t.lat == null || t.lng == null) continue;
  (byNomad.get(t.nomadRegion) ?? byNomad.set(t.nomadRegion, []).get(t.nomadRegion)).push([t.lng, t.lat]);
}
const nomadFeatures = [];
for (const [name, pts] of byNomad) {
  const ring = hull(pts);
  if (ring) nomadFeatures.push({ type: 'Feature', properties: { nomad: name, country: name.split(', ').pop() }, geometry: { type: 'Polygon', coordinates: [ring] } });
}
writeFileSync(join(OUT, 'nomad.geojson'), JSON.stringify({ type: 'FeatureCollection', features: nomadFeatures }));

// ── 5. cities.geojson — distinct visited city points ───────────────────────
const cityMap = new Map();
for (const t of travel.trips) {
  if (!t.city || t.lat == null || t.lng == null) continue;
  const k = `${t.city}|${t.country}`;
  if (!cityMap.has(k)) cityMap.set(k, { type: 'Feature', properties: { city: t.city, country: t.country }, geometry: { type: 'Point', coordinates: [t.lng, t.lat] } });
}
writeFileSync(join(OUT, 'cities.geojson'), JSON.stringify({ type: 'FeatureCollection', features: [...cityMap.values()] }));

// ── 6. capitals.json — country → [lat, lon] (COUNTRY-mode pin sits here) ─────
const capitals = {};
for (const t of travel.trips) {
  const c = t.countryCapital;
  if (t.country && c && c.lat != null && c.lng != null && !(t.country in capitals)) capitals[t.country] = [c.lat, c.lng];
}
writeFileSync(join(OUT, 'capitals.json'), JSON.stringify(capitals));

process.stderr.write(`\nDONE. nomad regions=${nomadFeatures.length}, cities=${cityMap.size}\n`);
