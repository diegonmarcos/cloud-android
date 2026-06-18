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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Navigation tab — a 3D "racing-car" driving view. The shared
 * [MapsMapFragment] starts tilted (55° pitch, high zoom) and, on a location
 * fix, follows the user's heading (bearing) like a racing-game chase cam.
 * A destination search drops the target pin; the bottom HUD shows live speed.
 *
 * Turn-by-turn maneuver decoding + voice is the next phase; the 3D camera +
 * heading-follow + speed HUD are the functional driving-view chrome.
 */
class NavigationFragment : Fragment() {

    private val mapFragment = MapsMapFragment.newInstance(nav3d = true, fab = true)
    private lateinit var speedValue: TextView
    private lateinit var destCard: MaterialCardView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val root = FrameLayout(ctx)

        val mapHost = FrameLayout(ctx).apply { id = View.generateViewId() }
        root.addView(mapHost, FrameLayout.LayoutParams(MATCH, MATCH))
        mapFragment.onUserLocation = { loc ->
            val kmh = if (loc.hasSpeed()) (loc.speed * 3.6f) else 0f
            speedValue.text = if (loc.hasSpeed()) "%.0f".format(kmh) else "—"
        }
        if (childFragmentManager.findFragmentById(mapHost.id) == null) {
            childFragmentManager.beginTransaction().replace(mapHost.id, mapFragment).commit()
        }

        // Top: destination search.
        destCard = SearchUi.searchCard(ctx, "Where to? Destination address or place…") { q ->
            setDestination(q)
        }
        root.addView(destCard, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP
            setMargins(dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), 0)
        })

        // Bottom: speed HUD + recenter.
        val hud = MaterialCardView(ctx).apply {
            radius = dp(20f); cardElevation = dp(8f); useCompatPadding = true
        }
        val hudRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(14f).toInt())
        }
        val speedCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        speedValue = TextView(ctx).apply {
            text = "—"
            textSize = 40f
            setTextColor(0xFF0B8043.toInt())
        }
        speedCol.addView(speedValue)
        speedCol.addView(TextView(ctx).apply {
            text = "km/h · 3D driving view"
            textSize = 12f
            setTextColor(0xFF5C5F5C.toInt())
        })
        hudRow.addView(speedCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        hudRow.addView(MaterialButton(ctx).apply {
            text = "Recenter"
            setOnClickListener { mapFragment.recenterOnUser() }
        })
        hud.addView(hudRow)
        root.addView(hud, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(8f).toInt(), 0, dp(8f).toInt(), dp(8f).toInt())
        })

        return root
    }

    private fun setDestination(query: String) {
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
                SearchUi.field(destCard).setText(hit.title)
                mapFragment.setPins(listOf(MapsMapFragment.Pin(hit.lat, hit.lon, MapsMapFragment.COLOR_RESULT)))
                Toast.makeText(ctx, "Destination set · ${hit.title}", Toast.LENGTH_SHORT).show()
                // Snap back to the 3D follow view from the user's position.
                mapFragment.recenterOnUser()
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
