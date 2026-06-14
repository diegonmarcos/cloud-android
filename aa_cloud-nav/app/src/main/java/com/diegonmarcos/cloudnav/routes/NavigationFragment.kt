package com.diegonmarcos.cloudnav.routes

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.NavShellChild
import com.diegonmarcos.cloudnav.maps.MapsMapFragment
import com.google.android.material.card.MaterialCardView

/**
 * Navigation tab — driving view. Live MapLibre map (from libs:maps) with a
 * turn-by-turn instruction banner overlaid at the top. The shell search bar
 * sets the destination; multi-routing shares the Routes stop model.
 *
 * Turn-by-turn guidance (maneuver decoding + voice) is the next phase — the
 * driving-view chrome here is the functional scaffold for it.
 */
class NavigationFragment : Fragment(), NavShellChild {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val root = FrameLayout(requireContext())

        val mapHost = FrameLayout(requireContext()).apply { id = View.generateViewId() }
        root.addView(mapHost, FrameLayout.LayoutParams(MATCH, MATCH))
        if (childFragmentManager.findFragmentById(mapHost.id) == null) {
            childFragmentManager.beginTransaction()
                .replace(mapHost.id, MapsMapFragment())
                .commit()
        }

        val banner = MaterialCardView(requireContext()).apply {
            radius = dp(16f); cardElevation = dp(6f); useCompatPadding = true
        }
        val col = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(14f).toInt())
        }
        col.addView(TextView(requireContext()).apply {
            text = "Driving"
            textSize = 16f
        })
        col.addView(TextView(requireContext()).apply {
            text = "Search a destination to start turn-by-turn navigation."
            textSize = 13f
            setPadding(0, dp(6f).toInt(), 0, 0)
        })
        banner.addView(col)
        root.addView(banner, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP
            setMargins(dp(12f).toInt(), dp(12f).toInt(), dp(12f).toInt(), 0)
        })
        return root
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
