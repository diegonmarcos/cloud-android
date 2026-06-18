package com.diegonmarcos.cloudnav.places

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.NavConfig
import com.diegonmarcos.cloudnav.PlaceCategory
import com.diegonmarcos.cloudnav.SearchUi
import com.diegonmarcos.cloudnav.maps.MapsMapFragment
import com.diegonmarcos.cloudnav.maps.MapsProviderClient
import com.diegonmarcos.cloudnav.maps.MapsStopsFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Places tab — the universal "find anything" surface. A live map with the
 * simplest search bar (free text → countries, cities, restaurants, anything)
 * + a row of category "islands" beneath it (Bars, Beaches, Gyms, …) like
 * Google Maps. After a search/category lookup the pins drop on the map AND a
 * swipe-up bottom sheet (peeking just above the bottom nav) lists the results;
 * tapping a row recenters the map on that place.
 */
class PlacesFragment : Fragment() {

    private val mapFragment = MapsMapFragment.newInstance(fab = true, style = "satellite")
    private lateinit var sheetBehavior: BottomSheetBehavior<View>
    private lateinit var sheetTitle: TextView
    private lateinit var resultsContainer: LinearLayout
    private var activeRadiusKm: Double = 0.0   // search scope: City/Country/World

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val root = CoordinatorLayout(ctx)

        // Map (fills).
        val mapHost = FrameLayout(ctx).apply { id = View.generateViewId() }
        root.addView(mapHost, CoordinatorLayout.LayoutParams(MATCH, MATCH))
        if (childFragmentManager.findFragmentById(mapHost.id) == null) {
            childFragmentManager.beginTransaction().replace(mapHost.id, mapFragment).commit()
        }

        // Top overlay: simple search + radius-scope islands + category islands.
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val searchCard = SearchUi.searchCard(ctx, "Search places, addresses, cities…") { q -> search(q) }
        top.addView(searchCard, LinearLayout.LayoutParams(MATCH, WRAP))

        // Radius scope (City / Country / World) — shown when the search bar is
        // focused; default = build.json default_search_scope (City).
        val scopes = NavConfig.searchScopes
        activeRadiusKm = scopes.firstOrNull { it.id == NavConfig.defaultScope }?.radiusKm ?: 0.0
        val scopeChips = ChipGroup(ctx).apply { isSingleLine = true; isSingleSelection = true; chipSpacingHorizontal = dp(6); setPadding(dp(4), 0, dp(4), 0) }
        scopes.forEach { sc ->
            scopeChips.addView(Chip(ctx).apply {
                id = View.generateViewId()
                text = if (sc.emoji.isBlank()) sc.label else "${sc.emoji} ${sc.label}"
                isCheckable = true
                isChecked = sc.id == NavConfig.defaultScope
                setOnClickListener { activeRadiusKm = sc.radiusKm }
            })
        }
        val scopeScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false; addView(scopeChips); visibility = View.GONE
        }
        top.addView(scopeScroll, LinearLayout.LayoutParams(MATCH, WRAP))
        SearchUi.field(searchCard).setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) scopeScroll.visibility = View.VISIBLE
        }

        val islands = ChipGroup(ctx).apply {
            isSingleLine = true
            chipSpacingHorizontal = dp(6)
            setPadding(dp(4), 0, dp(4), 0)
        }
        NavConfig.placeCategories.forEach { cat ->
            islands.addView(Chip(ctx).apply {
                text = if (cat.emoji.isBlank()) cat.label else "${cat.emoji} ${cat.label}"
                isClickable = true; isCheckable = false
                setOnClickListener { searchCategory(cat) }
            })
        }
        top.addView(
            HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(islands) },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        root.addView(top, CoordinatorLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP
            setMargins(dp(8), dp(8), dp(8), 0)
        })

        // Bottom sheet: results list (hidden until a search returns). The
        // sheet view already carries its CoordinatorLayout.LayoutParams (with
        // sheetBehavior) from buildSheet — add it WITHOUT params so that
        // behavior instance stays the attached one.
        root.addView(buildSheet(ctx))
        return root
    }

    private fun buildSheet(ctx: android.content.Context): View {
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MapsStopsFragment.COL_SURFACE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        // Drag handle.
        sheet.addView(View(ctx).apply {
            setBackgroundColor(0xFFCCCCCC.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8)
            }
        })
        sheetTitle = TextView(ctx).apply {
            text = "Results"
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setTextColor(MapsStopsFragment.COL_PRIMARY)
            setPadding(0, 0, 0, dp(8))
        }
        sheet.addView(sheetTitle)
        resultsContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        sheet.addView(ScrollView(ctx).apply { addView(resultsContainer) },
            LinearLayout.LayoutParams(MATCH, 0, 1f))
        sheetBehavior = BottomSheetBehavior<View>().apply {
            isHideable = true
            peekHeight = dp(72)
            state = BottomSheetBehavior.STATE_HIDDEN
        }
        sheet.layoutParams = CoordinatorLayout.LayoutParams(MATCH, MATCH).apply { behavior = sheetBehavior }
        return sheet
    }

    /** Free-text universal search → red pins + results list. Bounded by the
     *  active radius scope (City/Country/World) around the user (or map). */
    private fun search(query: String) {
        if (query.isBlank()) return
        val ctx = context ?: return
        val focus = mapFragment.myLatLon() ?: mapFragment.centerTarget()
        val radius = activeRadiusKm
        Thread {
            val hits = MapsProviderClient.forwardSearch(
                ctx, query,
                focusLat = focus?.first ?: 0.0,
                focusLon = focus?.second ?: 0.0,
                radiusKm = radius,
            )
            ui {
                if (hits.isEmpty()) { Toast.makeText(ctx, "No match for \"$query\"", Toast.LENGTH_SHORT).show(); return@ui }
                showResults(hits, "\"$query\"", MapsMapFragment.COLOR_RESULT)
            }
        }.start()
    }

    /** Category island → Overpass POI in the viewport → blue pins + list. */
    private fun searchCategory(cat: PlaceCategory) {
        val ctx = context ?: return
        val bounds = mapFragment.visibleBounds()
        if (bounds == null) { Toast.makeText(ctx, "Map still loading…", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(ctx, "Searching ${cat.label}…", Toast.LENGTH_SHORT).show()
        Thread {
            val hits = MapsProviderClient.poiSearch(ctx, cat.query, bounds[0], bounds[1], bounds[2], bounds[3])
            ui {
                if (hits.isEmpty()) { Toast.makeText(ctx, "No ${cat.label} in view — zoom/pan and retry", Toast.LENGTH_SHORT).show(); return@ui }
                showResults(hits, cat.label, MapsMapFragment.COLOR_PLACE)
            }
        }.start()
    }

    /** Drop pins, frame them, and populate + peek the results sheet. */
    private fun showResults(hits: List<MapsProviderClient.SearchHit>, label: String, color: String) {
        val ctx = context ?: return
        val pins = hits.map { MapsMapFragment.Pin(it.lat, it.lon, color) }
        mapFragment.setPins(pins)
        mapFragment.fitTo(pins)

        sheetTitle.text = "${hits.size} · $label"
        resultsContainer.removeAllViews()
        hits.forEach { hit ->
            resultsContainer.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                setPadding(0, dp(10), 0, dp(10))
                addView(TextView(ctx).apply {
                    text = hit.title
                    setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                    setTextColor(MapsStopsFragment.COL_PRIMARY)
                })
                hit.subtitle?.let { sub ->
                    addView(TextView(ctx).apply {
                        text = sub
                        setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                        setTextColor(MapsStopsFragment.COL_SECONDARY)
                    })
                }
                setOnClickListener {
                    mapFragment.recenter(hit.lat, hit.lon, 16.0)
                    sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                }
            })
            resultsContainer.addView(View(ctx).apply {
                setBackgroundColor(0x14000000)
                layoutParams = LinearLayout.LayoutParams(MATCH, 1)
            })
        }
        sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun ui(block: () -> Unit) {
        if (!isAdded) return
        requireActivity().runOnUiThread { if (isAdded) block() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
