package com.diegonmarcos.cloudnav.sky

import android.content.Context
import org.json.JSONObject
import kotlin.math.*

data class OrbitalElements(
    val name: String,
    val a: Double, val e: Double, val i: Double, val L: Double, val W: Double, val N: Double,
    val da: Double, val de: Double, val di: Double, val dL: Double, val dW: Double, val dN: Double,
)

data class BodyPosition(val name: String, val raDeg: Double, val decDeg: Double, val mag: Double)

/**
 * Positions of the Sun and major planets, from JPL's published "Keplerian Elements for
 * Approximate Positions of the Major Planets" (ssd.jpl.nasa.gov) -- a standard, publicly
 * documented low-precision method (~1 arcmin accuracy 1800-2050), reimplemented here from
 * the public formulas/elements (bundled in planets.json, see CREDITS.md), NOT copied from
 * any GPL-licensed source like Stardroid.
 *
 * Method, per JPL's own worked example:
 *  1. Propagate each element linearly by centuries-since-J2000 (T).
 *  2. Solve Kepler's equation M = E - e*sin(E) for eccentric anomaly E.
 *  3. Position in the orbital plane -> rotate by (w, i, Omega) into J2000 ecliptic x/y/z.
 *  4. Subtract Earth's heliocentric vector to get the geocentric vector (for the Sun,
 *     the geocentric vector is simply -earthHeliocentric, since the Sun sits at the
 *     origin of the heliocentric frame).
 *  5. Rotate ecliptic -> equatorial by the J2000 obliquity, then atan2 to RA/Dec.
 */
object PlanetEphemeris {
    private const val OBLIQUITY_DEG = 23.43928

    fun loadElements(context: Context): Map<String, OrbitalElements> {
        val root = JSONObject(context.assets.open("celestial/planets.json").bufferedReader().readText())
        val out = LinkedHashMap<String, OrbitalElements>()
        for (key in root.keys()) {
            val body = root.getJSONObject(key)
            // "sol" (Sun) and "lun" (Moon) both ship empty "elements" arrays in the
            // source data -- the Sun is computed geometrically from Earth's position
            // below, and the Moon needs actual lunar theory (not this heliocentric
            // Keplerian method), which isn't implemented; skip both rather than crash
            // on an empty array or fake a Moon position with the wrong physics.
            val elementsArr = body.optJSONArray("elements")
            if (elementsArr == null || elementsArr.length() == 0) continue
            val el = elementsArr.getJSONObject(0)
            // Minor bodies (Eris/Makemake/Haumea/Vesta/Ceres/Pallas -- dwarf planets and
            // asteroids, not among the 8 major planets actually asked for) ship a DIFFERENT
            // orbital-element schema in this data: mean anomaly M + mean motion n (deg/day)
            // + argument of perihelion w directly, instead of mean longitude L + centuries-
            // rate dL + longitude of perihelion W. Found via a real crash in CI (an
            // instrumented test caught "JSONException: No value for L" instead of the app
            // silently crashing) -- not implementing that second schema under time
            // pressure; skip these bodies explicitly rather than guess at it.
            if (!el.has("L") || !el.has("W")) continue
            out[key] = OrbitalElements(
                name = body.optString("en", body.optString("name", key)),
                a = el.getDouble("a"), e = el.getDouble("e"), i = el.getDouble("i"),
                L = el.getDouble("L"), W = el.getDouble("W"), N = el.getDouble("N"),
                da = el.optDouble("da", 0.0), de = el.optDouble("de", 0.0), di = el.optDouble("di", 0.0),
                dL = el.optDouble("dL", 0.0), dW = el.optDouble("dW", 0.0), dN = el.optDouble("dN", 0.0),
            )
        }
        return out
    }

    /** Julian centuries since J2000.0 (2000-01-01T12:00:00Z). */
    private fun centuriesSinceJ2000(unixMillis: Long): Double {
        val daysSinceJ2000 = (unixMillis / 86400000.0) - 10957.5
        return daysSinceJ2000 / 36525.0
    }

    /** Heliocentric ecliptic J2000 position (AU), per JPL's worked-example steps 1-3. */
    private fun heliocentricEcliptic(el: OrbitalElements, tCenturies: Double): DoubleArray {
        val a = el.a + el.da * tCenturies
        val e = el.e + el.de * tCenturies
        val iDeg = el.i + el.di * tCenturies
        val lDeg = el.L + el.dL * tCenturies
        val wBarDeg = el.W + el.dW * tCenturies
        val omegaDeg = el.N + el.dN * tCenturies

        val wDeg = wBarDeg - omegaDeg // argument of perihelion
        var mDeg = (lDeg - wBarDeg).mod(360.0)
        if (mDeg > 180.0) mDeg -= 360.0

        val eStarDeg = Math.toDegrees(e) // e* = e in radians-equivalent degrees, per JPL's degree-based iteration
        var eAnomDeg = mDeg + eStarDeg * sin(Math.toRadians(mDeg))
        for (iter in 0 until 10) {
            val dM = mDeg - (eAnomDeg - eStarDeg * sin(Math.toRadians(eAnomDeg)))
            val dE = dM / (1.0 - e * cos(Math.toRadians(eAnomDeg)))
            eAnomDeg += dE
            if (abs(dE) < 1e-7) break // converged -- no point iterating further
        }
        val eAnomRad = Math.toRadians(eAnomDeg)

        // Position in the orbital plane.
        val xOrb = a * (cos(eAnomRad) - e)
        val yOrb = a * sqrt(1 - e * e) * sin(eAnomRad)

        val wRad = Math.toRadians(wDeg)
        val iRad = Math.toRadians(iDeg)
        val omegaRad = Math.toRadians(omegaDeg)

        val cosW = cos(wRad); val sinW = sin(wRad)
        val cosO = cos(omegaRad); val sinO = sin(omegaRad)
        val cosI = cos(iRad); val sinI = sin(iRad)

        val xEcl = (cosW * cosO - sinW * sinO * cosI) * xOrb + (-sinW * cosO - cosW * sinO * cosI) * yOrb
        val yEcl = (cosW * sinO + sinW * cosO * cosI) * xOrb + (-sinW * sinO + cosW * cosO * cosI) * yOrb
        val zEcl = (sinW * sinI) * xOrb + (cosW * sinI) * yOrb
        return doubleArrayOf(xEcl, yEcl, zEcl)
    }

    private fun eclipticToEquatorialRaDec(x: Double, y: Double, z: Double): Pair<Double, Double> {
        val eps = Math.toRadians(OBLIQUITY_DEG)
        val xEq = x
        val yEq = y * cos(eps) - z * sin(eps)
        val zEq = y * sin(eps) + z * cos(eps)
        var raDeg = Math.toDegrees(atan2(yEq, xEq))
        if (raDeg < 0) raDeg += 360.0
        val decDeg = Math.toDegrees(atan2(zEq, sqrt(xEq * xEq + yEq * yEq)))
        return raDeg to decDeg
    }

    /** RA/Dec of the Sun and all loaded planets (skipping "ter"/Earth itself) at [unixMillis]. */
    fun positions(elements: Map<String, OrbitalElements>, unixMillis: Long): List<BodyPosition> {
        val t = centuriesSinceJ2000(unixMillis)
        val earthHelio = elements["ter"]?.let { heliocentricEcliptic(it, t) } ?: return emptyList()

        val out = ArrayList<BodyPosition>()
        // Sun: geocentric vector is the negative of Earth's heliocentric vector.
        val (sunRa, sunDec) = eclipticToEquatorialRaDec(-earthHelio[0], -earthHelio[1], -earthHelio[2])
        out.add(BodyPosition("Sun", sunRa, sunDec, -26.7))

        for ((key, el) in elements) {
            if (key == "ter") continue
            val helio = heliocentricEcliptic(el, t)
            val gx = helio[0] - earthHelio[0]
            val gy = helio[1] - earthHelio[1]
            val gz = helio[2] - earthHelio[2]
            val (ra, dec) = eclipticToEquatorialRaDec(gx, gy, gz)
            // Crude apparent-magnitude placeholder (distance-based, no phase-angle term) --
            // good enough for relative dot sizing, not photometrically precise.
            val dist = sqrt(gx * gx + gy * gy + gz * gz)
            val mag = 5.0 * log10(dist.coerceAtLeast(0.01)) - 1.0
            out.add(BodyPosition(el.name, ra, dec, mag))
        }
        return out
    }
}
