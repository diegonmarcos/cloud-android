package com.diegonmarcos.cloudnav

import java.net.URLDecoder

/**
 * Parses a `geo:` intent URI (RFC 5870 + Google's `q=` extension) into a target
 * the Places screen can act on. Pure/string-only — unit-tests without an
 * Android Context. Backs the "Cloud Nav is a maps app" manifest handler: when
 * any app fires a `geo:` location, Android offers Cloud Nav, which lands the
 * user on the Places map at that point (or runs the address search).
 *
 * Handled forms:
 *   geo:LAT,LON                    → Point
 *   geo:LAT,LON?z=ZOOM             → Point at zoom
 *   geo:0,0?q=free text address    → Query (forward search)
 *   geo:0,0?q=LAT,LON(Label)       → Point with label
 *   geo:LAT,LON?q=LAT,LON(Label)   → Point (q coords win, labelled)
 *   geo:LAT,LON?q=free text        → Point at LAT,LON labelled with the text
 */
sealed interface GeoTarget {
    data class Point(val lat: Double, val lon: Double, val label: String?, val zoom: Double?) : GeoTarget
    data class Query(val text: String) : GeoTarget
}

object GeoUri {
    private val COORD = Regex("""^\s*(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)\s*$""")
    // "lat,lon" optionally followed by "(label)" — the q= coordinate form.
    private val COORD_LABEL = Regex("""^\s*(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""")

    fun parse(raw: String?): GeoTarget? {
        val s = raw?.trim().orEmpty()
        if (!s.startsWith("geo:", ignoreCase = true)) return null
        val body = s.substring(4)
        val qMark = body.indexOf('?')
        val path = if (qMark >= 0) body.substring(0, qMark) else body
        val query = if (qMark >= 0) body.substring(qMark + 1) else ""

        val params = query.split('&').mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) null else it.substring(0, eq) to decode(it.substring(eq + 1))
        }.toMap()

        val zoom = params["z"]?.toDoubleOrNull()
        val pathCoord = COORD.find(path)
        val pLat = pathCoord?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val pLon = pathCoord?.groupValues?.getOrNull(2)?.toDoubleOrNull()
        val pathIsReal = pLat != null && pLon != null && !(pLat == 0.0 && pLon == 0.0)

        val q = params["q"]?.trim().orEmpty()
        if (q.isNotEmpty()) {
            COORD_LABEL.find(q)?.let { m ->
                val lat = m.groupValues[1].toDoubleOrNull()
                val lon = m.groupValues[2].toDoubleOrNull()
                val label = m.groupValues.getOrNull(3)?.ifBlank { null }
                if (lat != null && lon != null) return GeoTarget.Point(lat, lon, label, zoom)
            }
            // Free-text q: pin the path coords labelled with it when they're real,
            // else treat the text as a place search.
            return if (pathIsReal) GeoTarget.Point(pLat!!, pLon!!, q, zoom) else GeoTarget.Query(q)
        }
        // No q: a real path coordinate is a Point; (0,0) with nothing is meaningless.
        return if (pathIsReal) GeoTarget.Point(pLat!!, pLon!!, null, zoom) else null
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
