package com.diegonmarcos.cloudnav.maps

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Polygon

/**
 * Explored's choropleth layers (Approach A — MapLibre FillLayer), from compact
 * bundled GeoJSON (libs/maps/src/main/assets/geo/, produced by tools/geo/gen.mjs
 * from Natural Earth 10m + the front app's civilization taxonomy — never hand-drawn).
 *
 * Two paint modes:
 *   • PIN     — paint only the areas the traveller has VISITED (grey), i.e. the
 *               ones that carry a pin. The source holds visited-only features.
 *   • DEFAULT — paint the WHOLE world: each continent a distinct colour, each
 *               country a shade near its continent's colour, each region by its
 *               top-level region. The source holds all features, each tagged with
 *               a per-feature "col" property (data-driven fill).
 *
 * Geometry is delivered as a TYPED [FeatureCollection] (11.7 drops string
 * geojson). Countries are ~100m detail (8.5MB) → all parsing runs OFF the main
 * thread and layers are applied back on it, so opening Explored never janks.
 */
class MapsGeoLayers(private val ctx: Context, private val frag: MapsMapFragment) {

    enum class PaintMode { PIN, DEFAULT }

    enum class Layer(
        val id: String, val label: String, val asset: String,
        val key: String, val fill: String, val outline: String,
    ) {
        CONTINENTS("continents", "Continents", "geo/continents.geojson", "CONTINENT", "#9AA7B8", "#C3D0E0"),
        COUNTRIES("countries", "Countries", "geo/countries.geojson", "NAME", "#B8C0CC", "#E6ECF3"),
        // States / autonomous regions — admin-1, the first subdivision below
        // country (Brazil states, Spain autonomous communities…). Ordered right
        // after Countries in the painted-layers list.
        STATES("states", "States", "geo/states.geojson", "name", "#C7B29A", "#E7D8C6"),
        REGIONS("political", "Political-Cultural Regions", "geo/regions.geojson", "subregion", "#A896C0", "#CDBFE0"),
        NOMAD("nomad", "NomadMania Regions", "geo/nomad.geojson", "nomad", "#7FA8C9", "#B4D2E8"),
    }

    var enabled: Set<Layer> = emptySet(); private set
    var paintMode: PaintMode = PaintMode.PIN; private set

    private var visitedInput: Collection<String?> = emptyList()
    private var visitedPts: List<DoubleArray> = emptyList()   // [lat, lon] of visited cities
    private var visitedNames: Set<String> = emptySet()
    private var visitedContinents: Set<String> = emptySet()
    private var visitedSubregions: Set<String> = emptySet()
    private var visitedStates: Set<String> = emptySet()
    private var isoToContinent: Map<String, String> = emptyMap()

    private val ui = Handler(Looper.getMainLooper())
    private val baseCache = HashMap<Layer, List<Feature>>()   // parsed once (expensive)
    private val display = HashMap<Layer, FeatureCollection>() // built visited/coloured FC

    /** Configure and (re)build. Heavy parsing runs on a background thread.
     *  [cityPoints] are [lat, lon] of visited cities — used to mark States as
     *  visited by point-in-polygon (stops don't carry a state name). */
    fun configure(
        countryNames: Collection<String?>, cityPoints: List<DoubleArray>,
        layers: Set<Layer>, mode: PaintMode,
    ) {
        visitedInput = countryNames; visitedPts = cityPoints; enabled = layers; paintMode = mode; rebuild()
    }
    fun setEnabled(layers: Set<Layer>) { enabled = layers; rebuild() }
    fun setPaintMode(mode: PaintMode) { paintMode = mode; rebuild() }

    /** Re-apply the already-built display collections on the main thread — called
     *  on every style (re)load, since a style switch wipes added layers. */
    fun reinstall() = apply()

    private fun rebuild() {
        val names = visitedInput
        Thread {
            runCatching {
                computeVisited(names)
                val built = HashMap<Layer, FeatureCollection>()
                for (l in Layer.values()) if (l in enabled) built[l] = buildDisplayFC(l)
                ui.post { display.clear(); display.putAll(built); apply() }
            }
        }.start()
    }

    /** Apply enabled layers, remove disabled ones. Main thread only. */
    private fun apply() {
        for (l in Layer.values()) {
            val fc = display[l]
            if (l in enabled && fc != null) {
                frag.addFillLayer(
                    sourceId = "geo-src-${l.id}", fillId = "geo-fill-${l.id}",
                    outlineId = "geo-line-${l.id}", fc = fc,
                    fillColor = l.fill, fillOpacity = if (paintMode == PaintMode.DEFAULT) 0.7f else 0.55f,
                    lineColor = l.outline,
                    colorProp = if (paintMode == PaintMode.DEFAULT) "col" else null,
                )
            } else {
                frag.removeFillLayer("geo-src-${l.id}", "geo-fill-${l.id}", "geo-line-${l.id}")
            }
        }
    }

    private fun buildDisplayFC(layer: Layer): FeatureCollection {
        val base = baseFeatures(layer)
        return if (paintMode == PaintMode.DEFAULT) {
            base.forEach { it.addStringProperty("col", colorFor(layer, it)) }
            FeatureCollection.fromFeatures(base)
        } else {
            FeatureCollection.fromFeatures(base.filter { isVisited(layer, it) })
        }
    }

    private fun baseFeatures(layer: Layer): List<Feature> = synchronized(baseCache) {
        baseCache.getOrPut(layer) {
            runCatching {
                FeatureCollection.fromJson(ctx.assets.open(layer.asset).bufferedReader().use { it.readText() })
                    .features() ?: emptyList()
            }.getOrDefault(emptyList())
        }
    }

    private fun isVisited(layer: Layer, f: Feature): Boolean = when (layer) {
        Layer.NOMAD -> true
        Layer.COUNTRIES -> normalize(f.getStringProperty("NAME") ?: "") in visitedNames
        Layer.CONTINENTS -> (f.getStringProperty("CONTINENT") ?: "") in visitedContinents
        Layer.STATES -> (f.getStringProperty("name") ?: "") in visitedStates
        Layer.REGIONS -> (f.getStringProperty("subregion") ?: "") in visitedSubregions
    }

    /** DEFAULT-mode colour for a feature: continent-hued, countries a shade near
     *  their continent, regions by top-level region. */
    private fun colorFor(layer: Layer, f: Feature): String = when (layer) {
        Layer.CONTINENTS -> continentColor(f.getStringProperty("CONTINENT"))
        Layer.COUNTRIES -> countryShade(f.getStringProperty("CONTINENT"), f.getStringProperty("NAME") ?: "")
        // States shaded by their country's continent, keyed by state name.
        Layer.STATES -> countryShade(isoToContinent[f.getStringProperty("ISO2")], f.getStringProperty("name") ?: "")
        Layer.REGIONS -> regionColor(f.getStringProperty("region"))
        Layer.NOMAD -> layer.fill
    }

    private fun computeVisited(countryNames: Collection<String?>) {
        visitedNames = countryNames.filterNotNull().map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
        val nameToIso = HashMap<String, String>()
        val nameToContinent = HashMap<String, String>()
        val isoCont = HashMap<String, String>()
        baseFeatures(Layer.COUNTRIES).forEach { f ->
            val n = f.getStringProperty("NAME") ?: return@forEach
            val iso = f.getStringProperty("ISO2"); val cont = f.getStringProperty("CONTINENT")
            if (iso != null) nameToIso[normalize(n)] = iso
            if (cont != null) nameToContinent[normalize(n)] = cont
            if (iso != null && cont != null) isoCont[iso] = cont
        }
        isoToContinent = isoCont
        val visitedIso = visitedNames.mapNotNull { nameToIso[it] }.toSet()
        visitedContinents = visitedNames.mapNotNull { nameToContinent[it] }.toSet()
        visitedSubregions = visitedSubregionsFor(readMembers(), visitedIso)
        visitedStates = if (Layer.STATES in enabled || paintMode == PaintMode.DEFAULT) computeVisitedStates() else emptySet()
    }

    /** A state is visited if a visited city point falls inside it (bbox-prefiltered
     *  ray-cast). Runs on the background thread. */
    private fun computeVisitedStates(): Set<String> {
        if (visitedPts.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (f in baseFeatures(Layer.STATES)) {
            val name = f.getStringProperty("name") ?: continue
            if (name in out) continue
            val rings = outerRings(f)
            if (rings.isEmpty()) continue
            var minLon = 1e9; var minLat = 1e9; var maxLon = -1e9; var maxLat = -1e9
            rings.forEach { r -> r.forEach { minLon = minOf(minLon, it[0]); maxLon = maxOf(maxLon, it[0]); minLat = minOf(minLat, it[1]); maxLat = maxOf(maxLat, it[1]) } }
            for (p in visitedPts) {
                val lat = p[0]; val lon = p[1]
                if (lon < minLon || lon > maxLon || lat < minLat || lat > maxLat) continue
                if (rings.any { pointInRing(it, lon, lat) }) { out.add(name); break }
            }
        }
        return out
    }

    /** Outer rings (as [lon,lat] arrays) of a Polygon/MultiPolygon feature; holes
     *  ignored (fine for containment). */
    private fun outerRings(f: Feature): List<List<DoubleArray>> {
        fun ring(pts: List<org.maplibre.geojson.Point>) = pts.map { doubleArrayOf(it.longitude(), it.latitude()) }
        return when (val g = f.geometry()) {
            is Polygon -> listOfNotNull(g.coordinates().firstOrNull()?.let(::ring))
            is MultiPolygon -> g.coordinates().mapNotNull { it.firstOrNull()?.let(::ring) }
            else -> emptyList()
        }
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

    /** Feature count of the built display collection for [layer] — test hook. */
    fun visitedCount(layer: Layer): Int = display[layer]?.features()?.size ?: -1

    companion object {
        /** Normalise a country name for tolerant matching (see NE NAME vs the
         *  traveller's short names). Pure/tested. */
        fun normalize(name: String): String {
            var s = name.trim().lowercase()
            s = s.removePrefix("the ")
            s = ALIASES[s] ?: s
            return s.filter { it.isLetterOrDigit() || it == ' ' }.trim()
        }

        /** Subregions whose member countries intersect the visited ISO set. Pure/tested. */
        fun visitedSubregionsFor(members: List<RegionMembers>, visitedIso: Set<String>): Set<String> =
            members.filter { it.countries.any { c -> c in visitedIso } }.map { it.subregion }.toSet()

        /** Ray-cast point-in-polygon. [ring] is a closed/open list of [lon,lat];
         *  ([x],[y]) is (lon,lat). Pure/tested — used to mark visited States. */
        fun pointInRing(ring: List<DoubleArray>, x: Double, y: Double): Boolean {
            var inside = false
            var j = ring.size - 1
            for (i in ring.indices) {
                val xi = ring[i][0]; val yi = ring[i][1]
                val xj = ring[j][0]; val yj = ring[j][1]
                if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) inside = !inside
                j = i
            }
            return inside
        }

        private fun hsv(h: Float, s: Float, v: Float): String =
            "#%06X".format(Color.HSVToColor(floatArrayOf(((h % 360) + 360) % 360, s, v)) and 0xFFFFFF)

        // Distinct hue per continent — the DEFAULT-mode palette anchor. Pure/tested.
        private val CONTINENT_HUE = mapOf(
            "Africa" to 40f, "Asia" to 4f, "Europe" to 212f,
            "North America" to 130f, "South America" to 286f, "Oceania" to 170f,
        )
        private val REGION_HUE = mapOf(
            "EUROPE" to 212f, "ASIA" to 4f, "AMERICAS" to 130f, "AFRICA" to 40f, "OCEANIA" to 170f,
        )

        fun continentColor(continent: String?): String =
            hsv(CONTINENT_HUE[continent] ?: 205f, 0.55f, 0.80f)

        fun regionColor(region: String?): String =
            hsv(REGION_HUE[region] ?: 205f, 0.58f, 0.82f)

        /** A country's colour: its continent's hue, nudged and shaded per-country
         *  (deterministic from the name) so neighbours differ but all read as the
         *  same continent family. Pure/tested. */
        fun countryShade(continent: String?, name: String): String {
            val base = CONTINENT_HUE[continent] ?: 205f
            var h = 0
            for (ch in name) h = h * 31 + ch.code
            val jitter = ((h % 30) - 15).toFloat()          // ±15° within the family
            val sat = 0.45f + (((h / 7) % 30) / 100f)        // 0.45..0.75
            val value = 0.70f + (((h / 13) % 22) / 100f)     // 0.70..0.92
            return hsv(base + jitter, sat, value)
        }

        private val ALIASES = mapOf(
            "united states" to "united states of america",
            "usa" to "united states of america",
            "uk" to "united kingdom",
            "czech republic" to "czechia",
            "uae" to "united arab emirates",
            "bosnia and herzegovina" to "bosnia and herz.",
        )
    }
}
