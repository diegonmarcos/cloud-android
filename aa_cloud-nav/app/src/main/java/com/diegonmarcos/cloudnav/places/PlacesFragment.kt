package com.diegonmarcos.cloudnav.places

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.Icons
import com.diegonmarcos.cloudnav.NavConfig
import com.diegonmarcos.cloudnav.PlaceCategory
import com.diegonmarcos.cloudnav.SearchUi
import com.diegonmarcos.cloudnav.maps.MapsMapFragment
import com.diegonmarcos.cloudnav.maps.MapsProviderClient
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Places tab — the universal "find anything" surface. A live map with the
 * SIMPLEST search bar (free text → countries, cities, neighborhoods,
 * restaurants, bars, anything) plus a row of category "islands" beneath it
 * (Bars, Beaches, Gyms, ATMs, …) exactly like Google Maps. Categories are
 * data-driven from build.json::ui.places_categories ([NavConfig]).
 *
 *   • Type + search → forward geocode → red pins.
 *   • Tap a category island → Overpass POI lookup in the current viewport
 *     → blue pins.
 */
class PlacesFragment : Fragment() {

    private val mapFragment = MapsMapFragment.newInstance(fab = true)

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

        // Top overlay: simple search + category islands.
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        top.addView(
            SearchUi.searchCard(ctx, "Search places, addresses, cities…") { q -> search(q) },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )

        val islands = ChipGroup(ctx).apply {
            isSingleLine = true
            chipSpacingHorizontal = dp(6f).toInt()
            setPadding(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
        }
        NavConfig.placeCategories.forEach { cat ->
            islands.addView(Chip(ctx).apply {
                text = cat.label
                isClickable = true
                isCheckable = false
                setChipIconResource(Icons.place(ctx, cat.icon))
                setOnClickListener { searchCategory(cat) }
            })
        }
        top.addView(
            HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(islands)
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )

        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP
            setMargins(dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), 0)
        })
        return root
    }

    /** Free-text universal search → red pins. */
    private fun search(query: String) {
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
                if (hits.isEmpty()) {
                    Toast.makeText(ctx, "No match for \"$query\"", Toast.LENGTH_SHORT).show()
                    return@ui
                }
                val pins = hits.map { MapsMapFragment.Pin(it.lat, it.lon, MapsMapFragment.COLOR_RESULT) }
                mapFragment.setPins(pins)
                mapFragment.fitTo(pins)
                Toast.makeText(ctx, "${hits.size} result${if (hits.size == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Category island → Overpass POI lookup in the current viewport → blue pins. */
    private fun searchCategory(cat: PlaceCategory) {
        val ctx = context ?: return
        val bounds = mapFragment.visibleBounds()
        if (bounds == null) {
            Toast.makeText(ctx, "Map still loading…", Toast.LENGTH_SHORT).show()
            return
        }
        val south = bounds[0]
        val west  = bounds[1]
        val north = bounds[2]
        val east  = bounds[3]
        Toast.makeText(ctx, "Searching ${cat.label}…", Toast.LENGTH_SHORT).show()
        Thread {
            val hits = MapsProviderClient.poiSearch(ctx, cat.query, south, west, north, east)
            ui {
                if (hits.isEmpty()) {
                    Toast.makeText(ctx, "No ${cat.label} in view — zoom/pan and retry", Toast.LENGTH_SHORT).show()
                    return@ui
                }
                val pins = hits.map { MapsMapFragment.Pin(it.lat, it.lon, MapsMapFragment.COLOR_PLACE) }
                mapFragment.setPins(pins)
                mapFragment.fitTo(pins)
                Toast.makeText(ctx, "${hits.size} ${cat.label}", Toast.LENGTH_SHORT).show()
            }
        }.start()
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
