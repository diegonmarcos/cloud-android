package com.diegonmarcos.cloudnav.routes

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.SearchUi
import com.diegonmarcos.cloudnav.maps.MapsMapFragment
import com.diegonmarcos.cloudnav.maps.MapsProviderClient
import com.google.android.material.card.MaterialCardView

/**
 * Routes tab — multi-stop routing. A live map (shared [MapsMapFragment])
 * with a routing card on top. The start point defaults to "Your location";
 * each destination you search is geocoded (universal forward search) and
 * added to the ordered stop list + pinned on the map. "Add stop" lets you
 * chain several places into one route.
 *
 * The drawn route polyline (OSRM/Valhalla over the active provider) is the
 * next phase — this wires real geocoding + ordered multi-stop pins, the
 * functional scaffold for it.
 */
class RoutesFragment : Fragment() {

    private val mapFragment = MapsMapFragment.newInstance(fab = true)

    private data class Stop(val label: String, val pin: MapsMapFragment.Pin?)
    private val stops = mutableListOf(Stop("Your location", null))

    private lateinit var stopList: LinearLayout
    private lateinit var searchCard: MaterialCardView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val root = FrameLayout(ctx)

        val mapHost = FrameLayout(ctx).apply { id = View.generateViewId() }
        root.addView(mapHost, FrameLayout.LayoutParams(MATCH, MATCH))
        if (childFragmentManager.findFragmentById(mapHost.id) == null) {
            childFragmentManager.beginTransaction().replace(mapHost.id, mapFragment).commit()
        }

        val card = MaterialCardView(ctx).apply {
            radius = dp(16f); cardElevation = dp(6f); useCompatPadding = true
        }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
        }
        panel.addView(TextView(ctx).apply {
            text = "Route"
            textSize = 16f
            setTextColor(0xFF1A1C1A.toInt())
        })

        searchCard = SearchUi.searchCard(ctx, "Add a destination — place, address, city…") { q ->
            addDestination(q)
        }
        panel.addView(searchCard, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(6f).toInt()
        })

        stopList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(stopList)

        card.addView(panel)
        root.addView(card, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP
            setMargins(dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), 0)
        })

        renderStops()
        return root
    }

    private fun addDestination(query: String) {
        if (query.isBlank()) return
        val ctx = context ?: return
        val center = mapFragment.centerTarget()
        Thread {
            val hits = MapsProviderClient.forwardSearch(
                ctx, query,
                focusLat = center?.first ?: 0.0,
                focusLon = center?.second ?: 0.0,
            )
            ui {
                val hit = hits.firstOrNull()
                if (hit == null) {
                    Toast.makeText(ctx, "No match for \"$query\"", Toast.LENGTH_SHORT).show()
                    return@ui
                }
                stops.add(Stop(hit.title, MapsMapFragment.Pin(hit.lat, hit.lon, MapsMapFragment.COLOR_RESULT)))
                SearchUi.field(searchCard).text?.clear()
                renderStops()
                val pins = stops.mapNotNull { it.pin }
                mapFragment.setPins(pins)
                if (pins.isNotEmpty()) mapFragment.fitTo(pins)
            }
        }.start()
    }

    private fun renderStops() {
        stopList.removeAllViews()
        stops.forEachIndexed { i, s ->
            stopList.addView(TextView(requireContext()).apply {
                text = "${i + 1}.  ${s.label}"
                textSize = 14f
                setTextColor(if (s.pin == null) 0xFF0B8043.toInt() else 0xFF1A1C1A.toInt())
                setPadding(0, dp(4f).toInt(), 0, dp(4f).toInt())
            })
        }
        stopList.addView(TextView(requireContext()).apply {
            text = "Search above to add another stop"
            textSize = 12f
            setTextColor(0xFF5C5F5C.toInt())
            setPadding(0, dp(6f).toInt(), 0, 0)
        })
    }

    private fun ui(block: () -> Unit) {
        if (!isAdded) return
        requireActivity().runOnUiThread { if (isAdded) block() }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
