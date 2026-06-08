package com.diegonmarcos.superapp.health

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment

/**
 * MyHealth — Compose host. ONE fragment instance per page id (the
 * pageId is carried in the Bundle), which keeps the host plumbing
 * trivial: MainActivity instantiates HealthFragment.newInstance(pageId)
 * via the SectionPages dispatcher, and the [HealthScreen] composable
 * picks which page to render based on that id.
 *
 * The pageId list is the source of truth in
 * build.json::ui.sections[id=health].pages[] — never duplicate it
 * here. Adding a new HC record type to the dashboard:
 *   1. Add a page entry in build.json::ui.sections[id=health].pages[].
 *   2. Add a perms-mapping entry in [HealthConnectGateway.PAGE_PERMS]
 *      if the new page needs a permission subset.
 *   3. Add a `when` branch in [HealthScreen] for the new pageId.
 */
class HealthFragment : Fragment() {

    private val pageId: String
        get() = arguments?.getString(ARG_PAGE) ?: PAGE_DASHBOARD

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0414))) {
                    HealthScreen(pageId = pageId)
                }
            }
        }

    companion object {
        private const val ARG_PAGE = "pageId"

        /** Page id constants — kept here in addition to the JSON so
         *  Kotlin call sites (test, deep-link parser) get compile-time
         *  checks. JSON remains the canonical list. */
        const val PAGE_DASHBOARD   = "dashboard"
        const val PAGE_ACTIVITY    = "activity"
        const val PAGE_HEART       = "heart"
        const val PAGE_SLEEP       = "sleep"
        const val PAGE_BODY        = "body"
        const val PAGE_VITALS      = "vitals"
        const val PAGE_NUTRITION   = "nutrition"
        const val PAGE_WORKOUTS    = "workouts"
        const val PAGE_CYCLE       = "cycle"
        const val PAGE_TRENDS      = "trends"
        const val PAGE_SOURCES     = "sources"
        const val PAGE_PERMISSIONS = "permissions"
        const val PAGE_RAW         = "raw"
        const val PAGE_STATS       = "stats"
        const val PAGE_ABOUT       = "about"

        fun newInstance(pageId: String = PAGE_DASHBOARD): HealthFragment =
            HealthFragment().apply {
                arguments = Bundle().apply { putString(ARG_PAGE, pageId) }
            }
    }
}
