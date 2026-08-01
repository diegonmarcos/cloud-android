package com.diegonmarcos.cloudnav.sky

import android.content.Context
import org.json.JSONObject

data class Star(val raDeg: Double, val decDeg: Double, val mag: Double)
data class ConstellationSegment(val a: DoubleArray /* raDeg, decDeg */, val b: DoubleArray)
/** A single closed ring of a Milky Way outline polygon, in [raDeg, decDeg] pairs. */
data class SkyPolygon(val ring: List<DoubleArray>)

/**
 * Loads the real bundled sky catalog (assets/celestial/*.json, from d3-celestial —
 * see CREDITS.md) instead of a hand-typed list. GeoJSON's [lon, lat] here IS already
 * [RA in degrees represented in the -180..180 range, Dec in degrees] per the source
 * data's own conversion note -- feeding that lon straight in as raDeg to SkyMath.altAz
 * is correct without further conversion, since altAz only ever uses RA through a
 * mod-360 subtraction (lst - raDeg), and -180..180 vs 0..360 are the same values mod 360.
 */
object CelestialData {
    fun loadStars(context: Context, maxMag: Double = 6.0): List<Star> {
        val root = JSONObject(context.assets.open("celestial/stars.6.json").bufferedReader().readText())
        val features = root.getJSONArray("features")
        val out = ArrayList<Star>(features.length())
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val mag = f.getJSONObject("properties").optDouble("mag", 99.0)
            if (mag > maxMag) continue
            val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
            out.add(Star(coords.getDouble(0), coords.getDouble(1), mag))
        }
        return out
    }

    fun loadConstellationLines(context: Context): List<ConstellationSegment> {
        val root = JSONObject(context.assets.open("celestial/constellations.lines.json").bufferedReader().readText())
        val features = root.getJSONArray("features")
        val out = ArrayList<ConstellationSegment>()
        for (i in 0 until features.length()) {
            val geom = features.getJSONObject(i).getJSONObject("geometry")
            val multiLine = geom.getJSONArray("coordinates")
            for (li in 0 until multiLine.length()) {
                val line = multiLine.getJSONArray(li)
                for (pi in 0 until line.length() - 1) {
                    val p1 = line.getJSONArray(pi)
                    val p2 = line.getJSONArray(pi + 1)
                    out.add(ConstellationSegment(
                        doubleArrayOf(p1.getDouble(0), p1.getDouble(1)),
                        doubleArrayOf(p2.getDouble(0), p2.getDouble(1)),
                    ))
                }
            }
        }
        return out
    }

    /** Milky Way band outline(s), as filled polygons — several overlapping brightness bands. */
    fun loadMilkyWay(context: Context): List<SkyPolygon> {
        val root = JSONObject(context.assets.open("celestial/mw.json").bufferedReader().readText())
        val features = root.getJSONArray("features")
        val out = ArrayList<SkyPolygon>()
        for (i in 0 until features.length()) {
            val geom = features.getJSONObject(i).getJSONObject("geometry")
            val polygons = geom.getJSONArray("coordinates") // MultiPolygon: [ [ [ring...] ] ]
            for (pi in 0 until polygons.length()) {
                val rings = polygons.getJSONArray(pi)
                val ring = rings.getJSONArray(0) // outer ring only, ignore holes
                val pts = ArrayList<DoubleArray>(ring.length())
                for (ri in 0 until ring.length()) {
                    val p = ring.getJSONArray(ri)
                    pts.add(doubleArrayOf(p.getDouble(0), p.getDouble(1)))
                }
                out.add(SkyPolygon(pts))
            }
        }
        return out
    }
}
