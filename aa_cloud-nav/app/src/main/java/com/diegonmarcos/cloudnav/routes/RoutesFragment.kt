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
import com.diegonmarcos.cloudnav.SearchIsland
import com.diegonmarcos.cloudnav.maps.MapsMapFragment
import com.google.android.material.card.MaterialCardView

/**
 * Routes tab — live MapLibre map (from libs:maps) with a multi-stop routing
 * panel overlaid at the bottom. The shell search bar feeds destination
 * queries here; "Your location" is the implicit start point.
 *
 * Routing engine wiring (OSRM/Valhalla over the active POI provider) is the
 * next phase — the panel + stop list are the functional scaffold for it.
 */
class RoutesFragment : Fragment(), NavShellChild {

    private val stops = mutableListOf("Your location")
    private lateinit var stopList: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val root = FrameLayout(requireContext())

        // Live map background.
        val mapHost = FrameLayout(requireContext()).apply { id = View.generateViewId() }
        root.addView(mapHost, FrameLayout.LayoutParams(MATCH, MATCH))
        if (childFragmentManager.findFragmentById(mapHost.id) == null) {
            childFragmentManager.beginTransaction()
                .replace(mapHost.id, MapsMapFragment())
                .commit()
        }

        // Multi-stop routing panel.
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(16f)
            cardElevation = dp(6f)
            useCompatPadding = true
        }
        val panel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(14f).toInt())
        }
        panel.addView(TextView(requireContext()).apply {
            text = "Route"
            textSize = 16f
        })
        stopList = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(stopList)
        panel.addView(TextView(requireContext()).apply {
            text = "+ Add stop  (search above to set a destination)"
            textSize = 13f
            setPadding(0, dp(8f).toInt(), 0, 0)
            setOnClickListener { addStop("New destination") }
        })
        card.addView(panel)
        root.addView(card, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(12f).toInt(), 0, dp(12f).toInt(), dp(12f).toInt())
        })

        renderStops()
        return root
    }

    override fun onSearchSubmit(query: String) {
        if (query.isNotBlank()) addStop(query)
    }

    override fun onIslandTap(island: SearchIsland) {
        addStop(island.label)
    }

    private fun addStop(label: String) {
        stops.add(label)
        if (::stopList.isInitialized) renderStops()
    }

    private fun renderStops() {
        stopList.removeAllViews()
        stops.forEachIndexed { i, s ->
            stopList.addView(TextView(requireContext()).apply {
                text = "${i + 1}.  $s"
                textSize = 14f
                setPadding(0, dp(4f).toInt(), 0, dp(4f).toInt())
            })
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
