package com.diegonmarcos.superapp.onehand

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure filter behind the Centauri star's "last 9 recent apps" arc-menu —
 *  no Android framework, runs on the JVM. */
class CentaurusStarFilterTest {

    private val launchable = setOf("a.one", "a.two", "a.three", "a.four")

    @Test fun ownPackageIsExcluded() {
        assertEquals(
            listOf("a.two", "a.three"),
            pickRecent(listOf("a.one", "a.two", "a.three"), launchable, self = "a.one", limit = 9),
        )
    }

    @Test fun nonLaunchablePackagesAreDropped() {
        // "a.uninstalled" ranked by usage but no longer resolves to a launcher activity.
        assertEquals(
            listOf("a.one", "a.two"),
            pickRecent(listOf("a.one", "a.uninstalled", "a.two"), launchable, self = "", limit = 9),
        )
    }

    @Test fun capsAtLimit() {
        assertEquals(
            listOf("a.one", "a.two"),
            pickRecent(listOf("a.one", "a.two", "a.three", "a.four"), launchable, self = "", limit = 2),
        )
    }

    @Test fun duplicatesCollapse() {
        assertEquals(
            listOf("a.one", "a.two"),
            pickRecent(listOf("a.one", "a.one", "a.two"), launchable, self = "", limit = 9),
        )
    }

    @Test fun emptyRankedYieldsEmpty() {
        assertEquals(emptyList<String>(), pickRecent(emptyList(), launchable, self = "", limit = 9))
    }

    @Test fun ninthEntryStillIncluded() {
        val ranked = (1..12).map { "a.pkg$it" }
        val allLaunchable = ranked.toSet()
        assertEquals(9, pickRecent(ranked, allLaunchable, self = "", limit = 9).size)
    }
}
