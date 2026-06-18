package com.diegonmarcos.cloudnav.routes

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.SearchUi
import com.diegonmarcos.cloudnav.maps.MapsMapFragment
import com.diegonmarcos.cloudnav.maps.MapsProviderClient
import com.diegonmarcos.cloudnav.maps.MapsRouting
import com.diegonmarcos.cloudnav.maps.MapsStopsFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Routes tab — multi-stop routing with a drawn route line, a travel-mode
 * switcher (car/walk/bike/bus/boat/flight/swim) and an all-modes stats sheet.
 *
 *   • Start defaults to "Your location"; each searched stop offers the top-3
 *     candidates to choose from (no silent first-hit).
 *   • Tap a stop to edit it (remove / move / rename).
 *   • The mode chips redraw the route (real road geometry via Valhalla for
 *     car/walk/bike/bus; great-circle estimate for boat/flight/swim).
 *   • The ⤢ expand icon opens a sheet comparing distance + time across ALL
 *     modes at once.
 */
class RoutesFragment : Fragment() {

    private val mapFragment = MapsMapFragment.newInstance(fab = true)

    private data class Stop(var label: String, var latLon: Pair<Double, Double>?)
    private val stops = mutableListOf(Stop("Your location", null))

    private val modes by lazy { MapsRouting.modes() }
    private var activeModeId = MapsRouting.defaultModeId()

    private lateinit var stopList: LinearLayout
    private lateinit var searchCard: MaterialCardView
    private lateinit var summary: TextView
    private lateinit var statsBehavior: BottomSheetBehavior<View>
    private lateinit var statsContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val root = CoordinatorLayout(ctx)

        val mapHost = FrameLayout(ctx).apply { id = View.generateViewId() }
        root.addView(mapHost, CoordinatorLayout.LayoutParams(MATCH, MATCH))
        // Recompute once the user locates (the "Your location" origin resolves).
        // onUserLocation uses android.location.Location — no MapLibre type leaks
        // into the app module (unlike onMapReady).
        mapFragment.onUserLocation = { recomputeRoute() }
        if (childFragmentManager.findFragmentById(mapHost.id) == null) {
            childFragmentManager.beginTransaction().replace(mapHost.id, mapFragment).commit()
        }

        // Top routing card.
        val card = MaterialCardView(ctx).apply { radius = dpf(16f); cardElevation = dpf(6f); useCompatPadding = true }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        // Title row + expand-stats.
        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(ctx).apply {
            text = "Route"; textSize = 16f; setTextColor(COL_PRIMARY)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        titleRow.addView(TextView(ctx).apply {
            text = "⤢"; textSize = 20f; setTextColor(COL_ACCENT)
            setPadding(dp(8), 0, dp(8), 0); isClickable = true
            contentDescription = "All travel options"
            setOnClickListener { openStats() }
        })
        panel.addView(titleRow)

        // Search + (+).
        val searchRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        searchCard = SearchUi.searchCard(ctx, "Add a stop — place, address, city…") { q -> addDestination(q) }
        searchRow.addView(searchCard, LinearLayout.LayoutParams(0, WRAP, 1f))
        searchRow.addView(FloatingActionButton(ctx).apply {
            setImageResource(android.R.drawable.ic_input_add)
            contentDescription = "Add stop"; size = FloatingActionButton.SIZE_MINI
            setOnClickListener { addDestination(SearchUi.field(searchCard).text.toString()) }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
        panel.addView(searchRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        // Mode switcher.
        val modeChips = ChipGroup(ctx).apply { isSingleLine = true; isSingleSelection = true; chipSpacingHorizontal = dp(6) }
        modes.forEachIndexed { i, m ->
            modeChips.addView(Chip(ctx).apply {
                id = View.generateViewId()
                text = "${m.emoji} ${m.label}"
                isCheckable = true
                isChecked = m.id == activeModeId
                setOnClickListener { activeModeId = m.id; recomputeRoute() }
            })
        }
        panel.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(modeChips) },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        // Stops list.
        stopList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(stopList)

        // Active-mode summary.
        summary = TextView(ctx).apply {
            text = "Add a destination to see the route"; textSize = 13f; setTextColor(COL_SECONDARY)
            setPadding(0, dp(6), 0, 0)
        }
        panel.addView(summary)

        card.addView(panel)
        root.addView(card, CoordinatorLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP; setMargins(dp(8), dp(8), dp(8), 0)
        })

        // All-modes stats bottom sheet.
        root.addView(buildStatsSheet(ctx))

        renderStops()
        return root
    }

    private fun buildStatsSheet(ctx: android.content.Context): View {
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MapsStopsFragment.COL_SURFACE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        sheet.addView(View(ctx).apply {
            setBackgroundColor(0xFFCCCCCC.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) }
        })
        sheet.addView(TextView(ctx).apply {
            text = "Travel options"; setTextColor(COL_PRIMARY)
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead); setPadding(0, 0, 0, dp(8))
        })
        statsContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        sheet.addView(ScrollView(ctx).apply { addView(statsContainer) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        statsBehavior = BottomSheetBehavior<View>().apply {
            isHideable = true; peekHeight = dp(56); state = BottomSheetBehavior.STATE_HIDDEN
        }
        sheet.layoutParams = CoordinatorLayout.LayoutParams(MATCH, MATCH).apply { behavior = statsBehavior }
        return sheet
    }

    // ── stops ────────────────────────────────────────────────────────
    private fun addDestination(query: String) {
        if (query.isBlank()) return
        val ctx = context ?: return
        val center = mapFragment.centerTarget()
        Thread {
            val hits = MapsProviderClient.forwardSearch(ctx, query, center?.first ?: 0.0, center?.second ?: 0.0)
            ui {
                if (hits.isEmpty()) { Toast.makeText(ctx, "No match for \"$query\"", Toast.LENGTH_SHORT).show(); return@ui }
                SearchUi.chooseResult(ctx, hits) { hit ->
                    stops.add(Stop(hit.title, hit.lat to hit.lon))
                    SearchUi.field(searchCard).text?.clear()
                    renderStops(); recomputeRoute()
                }
            }
        }.start()
    }

    private fun renderStops() {
        stopList.removeAllViews()
        stops.forEachIndexed { i, s ->
            stopList.addView(TextView(requireContext()).apply {
                text = "${i + 1}.  ${s.label}"
                textSize = 14f
                setTextColor(if (s.latLon == null) COL_ACCENT else COL_PRIMARY)
                setPadding(0, dp(4), 0, dp(4)); isClickable = true
                setOnClickListener { editStop(i) }
            })
        }
    }

    private fun editStop(index: Int) {
        val ctx = context ?: return
        val actions = arrayOf("Rename / re-search", "Move up", "Move down", "Remove")
        AlertDialog.Builder(ctx)
            .setTitle(stops[index].label)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> renameStop(index)
                    1 -> if (index > 0) { stops.swap(index, index - 1); renderStops(); recomputeRoute() }
                    2 -> if (index < stops.size - 1) { stops.swap(index, index + 1); renderStops(); recomputeRoute() }
                    3 -> { stops.removeAt(index); renderStops(); recomputeRoute() }
                }
            }
            .show()
    }

    private fun renameStop(index: Int) {
        val ctx = context ?: return
        val input = EditText(ctx).apply { setText(stops[index].label); setSelection(text.length) }
        AlertDialog.Builder(ctx)
            .setTitle("Edit stop")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val q = input.text.toString()
                if (q.isBlank()) return@setPositiveButton
                val center = mapFragment.centerTarget()
                Thread {
                    val hits = MapsProviderClient.forwardSearch(ctx, q, center?.first ?: 0.0, center?.second ?: 0.0)
                    ui {
                        if (hits.isEmpty()) { Toast.makeText(ctx, "No match", Toast.LENGTH_SHORT).show(); return@ui }
                        SearchUi.chooseResult(ctx, hits) { hit ->
                            stops[index] = Stop(hit.title, hit.lat to hit.lon)
                            renderStops(); recomputeRoute()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun MutableList<Stop>.swap(a: Int, b: Int) { val t = this[a]; this[a] = this[b]; this[b] = t }

    // ── routing ──────────────────────────────────────────────────────
    private fun coordsForRoute(): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (s in stops) {
            val ll = s.latLon ?: if (s.label == "Your location") mapFragment.myLatLon() else null
            if (ll != null) out.add(doubleArrayOf(ll.first, ll.second))
        }
        return out
    }

    private fun recomputeRoute() {
        if (!isAdded) return
        val pins = stops.mapNotNull { it.latLon }.map { MapsMapFragment.Pin(it.first, it.second, MapsMapFragment.COLOR_RESULT) }
        mapFragment.setPins(pins)
        val coords = coordsForRoute()
        if (coords.size < 2) {
            mapFragment.clearRoute()
            summary.text = if (stops.any { it.latLon != null }) "Tap the locate button to set your start" else "Add a destination to see the route"
            return
        }
        val mode = modes.firstOrNull { it.id == activeModeId } ?: return
        summary.text = "Routing…"
        val ctx = context ?: return
        Thread {
            val r = MapsRouting.route(mode, coords)
            ui {
                if (r == null) { summary.text = "No ${mode.label.lowercase()} route found"; mapFragment.clearRoute(); return@ui }
                mapFragment.setRoute(r.geometry)
                summary.text = "${mode.emoji} ${MapsRouting.fmtDistance(r.distanceKm)} · ${MapsRouting.fmtDuration(r.durationSec)}" +
                    if (r.estimated) "  (est.)" else ""
                if (pins.isNotEmpty()) mapFragment.fitTo(pins)
            }
        }.start()
    }

    private fun openStats() {
        val ctx = context ?: return
        val coords = coordsForRoute()
        statsContainer.removeAllViews()
        if (coords.size < 2) {
            statsContainer.addView(TextView(ctx).apply {
                text = "Add at least one destination (and your location) to compare travel options."
                setTextColor(COL_SECONDARY)
            })
            statsBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            return
        }
        modes.forEach { mode ->
            val row = TextView(ctx).apply {
                text = "${mode.emoji} ${mode.label}     …"
                textSize = 15f; setTextColor(COL_PRIMARY); setPadding(0, dp(10), 0, dp(10))
            }
            statsContainer.addView(row)
            Thread {
                val r = MapsRouting.route(mode, coords)
                ui {
                    row.text = "${mode.emoji} ${mode.label}     " + if (r == null) "—" else
                        "${MapsRouting.fmtDistance(r.distanceKm)} · ${MapsRouting.fmtDuration(r.durationSec)}" +
                            if (r.estimated) " (est.)" else ""
                }
            }.start()
        }
        statsBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun ui(block: () -> Unit) { if (!isAdded) return; requireActivity().runOnUiThread { if (isAdded) block() } }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val COL_PRIMARY = 0xFF1A1C1A.toInt()
        const val COL_SECONDARY = 0xFF5C5F5C.toInt()
        const val COL_ACCENT = 0xFF0B8043.toInt()
    }
}
