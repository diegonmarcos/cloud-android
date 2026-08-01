package com.diegonmarcos.cloudnav.sky

/**
 * Small hand-picked bright-star catalog (RA/Dec in J2000 degrees, visual magnitude),
 * NOT a port of Stardroid (GPLv3, license-incompatible) or the full Yale BSC5 -- this
 * is a deliberately minimal v1 covering a handful of recognizable constellations.
 * ponytail: ~40 stars / 6 constellations, add more from a permissively-licensed
 * catalog (e.g. HYG database, CC0) if fuller sky coverage is wanted later.
 */
data class Star(val name: String, val raDeg: Double, val decDeg: Double, val mag: Double)

data class ConstellationLine(val name: String, val starIndices: List<Pair<Int, Int>>)

object StarCatalog {
    val stars: List<Star> = listOf(
        // Ursa Major (Big Dipper)
        Star("Dubhe", 165.43, 61.75, 1.79),
        Star("Merak", 165.46, 56.38, 2.37),
        Star("Phecda", 178.46, 53.69, 2.44),
        Star("Megrez", 183.86, 57.03, 3.31),
        Star("Alioth", 193.51, 55.96, 1.77),
        Star("Mizar", 200.98, 54.93, 2.23),
        Star("Alkaid", 206.89, 49.31, 1.86),
        // Orion
        Star("Betelgeuse", 88.79, 7.41, 0.42),
        Star("Rigel", 78.63, -8.20, 0.13),
        Star("Bellatrix", 81.28, 6.35, 1.64),
        Star("Mintaka", 83.00, -0.30, 2.23),
        Star("Alnilam", 84.05, -1.20, 1.69),
        Star("Alnitak", 85.19, -1.94, 1.74),
        Star("Saiph", 86.94, -9.67, 2.06),
        // Cassiopeia
        Star("Schedar", 10.13, 56.54, 2.24),
        Star("Caph", 2.29, 59.15, 2.28),
        Star("Gamma Cas", 14.18, 60.72, 2.47),
        Star("Ruchbah", 21.45, 60.24, 2.68),
        Star("Segin", 28.60, 63.67, 3.35),
        // Crux (Southern Cross)
        Star("Acrux", 186.65, -63.10, 0.77),
        Star("Mimosa", 191.93, -59.69, 1.25),
        Star("Gacrux", 187.79, -57.11, 1.63),
        Star("Imai", 190.38, -58.75, 2.79),
        // Scorpius
        Star("Antares", 247.35, -26.43, 0.96),
        Star("Shaula", 263.40, -37.10, 1.62),
        Star("Sargas", 264.33, -42.99, 1.86),
        Star("Dschubba", 240.08, -22.62, 2.29),
        // Southern Cross neighbour / navigation
        Star("Sirius", 101.63, -16.72, -1.46),
        Star("Canopus", 95.99, -52.70, -0.74),
        Star("Polaris", 37.95, 89.26, 1.98),
        Star("Vega", 279.23, 38.78, 0.03),
        Star("Arcturus", 213.92, 19.18, -0.05),
        Star("Capella", 79.17, 46.00, 0.08),
    )

    private val byName = stars.withIndex().associate { (i, s) -> s.name to i }
    private fun idx(name: String) = byName.getValue(name)

    val lines: List<ConstellationLine> = listOf(
        ConstellationLine("Ursa Major", listOf(
            idx("Alkaid") to idx("Mizar"), idx("Mizar") to idx("Alioth"),
            idx("Alioth") to idx("Megrez"), idx("Megrez") to idx("Phecda"),
            idx("Phecda") to idx("Merak"), idx("Merak") to idx("Dubhe"),
            idx("Dubhe") to idx("Megrez"),
        )),
        ConstellationLine("Orion", listOf(
            idx("Betelgeuse") to idx("Bellatrix"), idx("Bellatrix") to idx("Mintaka"),
            idx("Mintaka") to idx("Alnilam"), idx("Alnilam") to idx("Alnitak"),
            idx("Alnitak") to idx("Saiph"), idx("Saiph") to idx("Rigel"),
            idx("Rigel") to idx("Mintaka"), idx("Betelgeuse") to idx("Alnitak"),
        )),
        ConstellationLine("Cassiopeia", listOf(
            idx("Caph") to idx("Schedar"), idx("Schedar") to idx("Gamma Cas"),
            idx("Gamma Cas") to idx("Ruchbah"), idx("Ruchbah") to idx("Segin"),
        )),
        ConstellationLine("Crux", listOf(
            idx("Gacrux") to idx("Acrux"), idx("Mimosa") to idx("Imai"),
        )),
        ConstellationLine("Scorpius", listOf(
            idx("Dschubba") to idx("Antares"), idx("Antares") to idx("Sargas"),
            idx("Sargas") to idx("Shaula"),
        )),
    )
}
