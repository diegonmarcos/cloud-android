package com.diegonmarcos.cloudnav.maps

import android.content.Context
import org.json.JSONArray
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection

/**
 * Explored's choropleth layers (Approach A — MapLibre FillLayer). Paints the
 * areas the traveller has VISITED, at several aggregation levels, from compact
 * bundled GeoJSON (libs/maps/src/main/assets/geo/, produced by tools/geo/gen.mjs
 * from Natural Earth + the front app's civilization taxonomy — never hand-drawn).
 *
 * The fill source holds VISITED-ONLY features (so "paint visited in light gray"
 * is literally the source content — no per-feature paint expression needed).
 * Geometry is delivered to MapLibre as a TYPED [FeatureCollection], never a JSON
 * string (11.7 silently drops string geojson — see MapsMapFragment).
 *
 * Layers mirror the front app's taxonomy (Political-Cultural Regions, Countries,
 * NomadMania Regions) plus Continents. Cities stay on the existing pin overlay.
 */
class MapsGeoLayers(private val ctx: Context, private val frag: MapsMapFragment) {

    /** Data-driven layer catalog. [key] is the GeoJSON property matched against
     *  the visited set; [asset] is the bundled file; colours are the fill/outline. */
    enum class Layer(
        val id: String, val label: String, val asset: String,
        val key: String, val fill: String, val outline: String,
    ) {
        CONTINENTS("continents", "Continents", "geo/continents.geojson", "CONTINENT", "#9AA7B8", "#C3D0E0"),
        COUNTRIES("countries", "Countries", "geo/countries.geojson", "NAME", "#B8C0CC", "#E6ECF3"),
        REGIONS("political", "Political-Cultural Regions", "geo/regions.geojson", "subregion", "#A896C0", "#CDBFE0"),
        NOMAD("nomad", "NomadMania Regions", "geo/nomad.geojson", "nomad", "#7FA8C9", "#B4D2E8"),
    }

    var enabled: Set<Layer> = emptySet()
        private set

    private var visitedNames: Set<String> = emptySet()      // normalised country names
    private var visitedContinents: Set<String> = emptySet()
    private var visitedSubregions: Set<String> = emptySet()

    // Cache parsed assets so toggling doesn't re-read/re-parse each time.
    private val fcCache = HashMap<Layer, FeatureCollection>()

    /** Recompute visited sets from the countries the traveller has been to
     *  (names as stored on stops). Continents/regions are derived via the
     *  bundled countries asset + region-members.json. */
    fun setVisitedCountries(countryNames: Collection<String?>) {
        visitedNames = countryNames.filterNotNull().map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
        val nameToIso = HashMap<String, String>()
        val nameToContinent = HashMap<String, String>()
        runCatching {
            val fc = load(Layer.COUNTRIES)
            fc.features()?.forEach { f ->
                val n = f.getStringProperty("NAME") ?: return@forEach
                f.getStringProperty("ISO2")?.let { nameToIso[normalize(n)] = it }
                f.getStringProperty("CONTINENT")?.let { nameToContinent[normalize(n)] = it }
            }
        }
        val visitedIso = visitedNames.mapNotNull { nameToIso[it] }.toSet()
        visitedContinents = visitedNames.mapNotNull { nameToContinent[it] }.toSet()
        visitedSubregions = visitedSubregionsFor(readMembers(), visitedIso)
    }

    fun setEnabled(layers: Set<Layer>) { enabled = layers; reinstall() }

    /** (Re)install every enabled layer's fill and drop disabled ones. Safe to
     *  call on every style (re)load — layers are wiped by a style switch. */
    fun reinstall() {
        for (layer in Layer.values()) {
            if (layer in enabled) {
                val fc = visitedFeatures(layer)
                frag.addFillLayer(
                    sourceId = "geo-src-${layer.id}", fillId = "geo-fill-${layer.id}",
                    outlineId = "geo-line-${layer.id}", fc = fc,
                    fillColor = layer.fill, fillOpacity = 0.55f, lineColor = layer.outline,
                )
            } else {
                frag.removeFillLayer("geo-src-${layer.id}", "geo-fill-${layer.id}", "geo-line-${layer.id}")
            }
        }
    }

    /** Feature count of the visited-only collection for [layer] — test hook. */
    fun visitedCount(layer: Layer): Int = visitedFeatures(layer).features()?.size ?: 0

    /** The visited-only FeatureCollection for [layer]. NOMAD/anything with no
     *  key is entirely the traveller's own data → all features are "visited". */
    private fun visitedFeatures(layer: Layer): FeatureCollection {
        val all = load(layer).features() ?: return FeatureCollection.fromFeatures(emptyList())
        val kept = all.filter { isVisited(layer, it) }
        return FeatureCollection.fromFeatures(kept)
    }

    private fun isVisited(layer: Layer, f: Feature): Boolean = when (layer) {
        Layer.NOMAD -> true
        Layer.COUNTRIES -> normalize(f.getStringProperty("NAME") ?: "") in visitedNames
        Layer.CONTINENTS -> (f.getStringProperty("CONTINENT") ?: "") in visitedContinents
        Layer.REGIONS -> (f.getStringProperty("subregion") ?: "") in visitedSubregions
    }

    private fun load(layer: Layer): FeatureCollection = fcCache.getOrPut(layer) {
        FeatureCollection.fromJson(ctx.assets.open(layer.asset).bufferedReader().use { it.readText() })
    }

    private fun readMembers(): List<RegionMembers> = runCatching {
        val arr = JSONArray(ctx.assets.open("geo/region-members.json").bufferedReader().use { it.readText() })
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val cs = o.getJSONArray("countries")
            RegionMembers(o.getString("subregion"), (0 until cs.length()).map { cs.getString(it) }.toSet())
        }
    }.getOrDefault(emptyList())

    data class RegionMembers(val subregion: String, val countries: Set<String>)

    companion object {
        /** Normalise a country name for tolerant matching between the traveller's
         *  stored names and Natural Earth's NAME field (e.g. "United States" vs
         *  "United States of America"). Pure/tested. */
        fun normalize(name: String): String {
            var s = name.trim().lowercase()
            s = s.removePrefix("the ")
            s = ALIASES[s] ?: s
            return s.filter { it.isLetterOrDigit() || it == ' ' }.trim()
        }

        /** Subregions whose member countries intersect the visited ISO set. Pure/tested. */
        fun visitedSubregionsFor(members: List<RegionMembers>, visitedIso: Set<String>): Set<String> =
            members.filter { it.countries.any { c -> c in visitedIso } }.map { it.subregion }.toSet()

        // Minimal name aliases where the traveller's short name ≠ Natural Earth NAME.
        private val ALIASES = mapOf(
            "united states" to "united states of america",
            "usa" to "united states of america",
            "uk" to "united kingdom",
            "czech republic" to "czechia",
            "south korea" to "south korea", "north korea" to "north korea",
            "russia" to "russia", "uae" to "united arab emirates",
            "bosnia and herzegovina" to "bosnia and herz.",
        )
    }
}
