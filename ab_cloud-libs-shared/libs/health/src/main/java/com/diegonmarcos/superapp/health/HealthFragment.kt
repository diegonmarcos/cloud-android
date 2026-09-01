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
 * picks which top-level page to render based on that id.
 *
 * Three top-level pages — Summary / Timeline / Configs — mirror
 * build.json::ui.sections[id=health].pages[]. Per-metric drill-down
 * (Activity, Heart, Sleep, Body, Vitals, Nutrition, Workouts, Cycle)
 * is INTERNAL Compose state inside each page, driven by the
 * `metrics` list in the same section.
 *
 * Adding/removing a metric: edit `metrics` in build.json — no Kotlin
 * change needed (the list is baked into BuildConfig.UI_HEALTH_METRICS_B64
 * at build time and decoded by [HealthMetrics]).
 */
class HealthFragment : Fragment() {

    private val pageId: String
        get() = arguments?.getString(ARG_PAGE) ?: PAGE_SUMMARY

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0414))) {
                    HealthScreen(initialPageId = pageId)
                }
            }
        }

    companion object {
        private const val ARG_PAGE = "pageId"

        /** Page id constants — three top-level pages, mirrored from
         *  build.json::ui.sections[id=health].pages[]. Per-metric
         *  drill-down (Activity / Heart / Sleep / Body / Vitals /
         *  Nutrition / Workouts / Cycle) is internal Compose state
         *  inside each page, driven by the metrics list baked into
         *  BuildConfig.UI_HEALTH_METRICS_B64. */
        const val PAGE_SUMMARY  = "summary"
        const val PAGE_TIMELINE = "timeline"
        const val PAGE_CONFIGS  = "configs"

        fun newInstance(pageId: String = PAGE_SUMMARY): HealthFragment =
            HealthFragment().apply {
                arguments = Bundle().apply { putString(ARG_PAGE, pageId) }
            }
    }
}
