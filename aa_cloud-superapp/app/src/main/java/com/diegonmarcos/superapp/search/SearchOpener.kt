package com.diegonmarcos.superapp.search
import com.diegonmarcos.superapp.launcher.AppDrawerSheetFragment

/**
 * Implemented by the host Activity so child fragments (currently
 * [AppDrawerSheetFragment]) can ask for the SearchSheet to slide in.
 *
 * Previously the activity-level toolbar carried a global search affordance;
 * search has since moved INTO the Home Apps page only, so the search bar
 * lives inside that fragment and bubbles up via this interface.
 */
interface SearchOpener {
    fun openSearchSheet()
}
