package com.diegonmarcos.cloudnav.sky

import kotlin.math.*

/** RA/Dec (equatorial, J2000-ish, no precession correction) -> Alt/Az for an observer. */
object SkyMath {
    /** @return Pair(altitudeDeg, azimuthDeg) — azimuth measured clockwise from north. */
    fun altAz(raDeg: Double, decDeg: Double, latDeg: Double, lonDeg: Double, unixMillis: Long): Pair<Double, Double> {
        val lst = localSiderealTimeDeg(lonDeg, unixMillis)
        var hourAngle = lst - raDeg
        hourAngle = ((hourAngle + 180.0).mod(360.0)) - 180.0
        val ha = Math.toRadians(hourAngle)
        val dec = Math.toRadians(decDeg)
        val lat = Math.toRadians(latDeg)

        val sinAlt = sin(dec) * sin(lat) + cos(dec) * cos(lat) * cos(ha)
        val alt = asin(sinAlt.coerceIn(-1.0, 1.0))

        val cosAz = (sin(dec) - sin(alt) * sin(lat)) / (cos(alt) * cos(lat))
        var az = acos(cosAz.coerceIn(-1.0, 1.0))
        if (sin(ha) > 0.0) az = 2 * PI - az

        return Math.toDegrees(alt) to Math.toDegrees(az)
    }

    /** Greenwich sidereal time (simplified, no nutation) + observer longitude. */
    private fun localSiderealTimeDeg(lonDeg: Double, unixMillis: Long): Double {
        val daysSinceJ2000 = (unixMillis / 86400000.0) - 10957.5 // 2000-01-01T00:00Z epoch day
        val gmstHours = 18.697374558 + 24.06570982441908 * daysSinceJ2000
        val gmstDeg = (gmstHours.mod(24.0)) * 15.0
        return (gmstDeg + lonDeg).mod(360.0)
    }
}
