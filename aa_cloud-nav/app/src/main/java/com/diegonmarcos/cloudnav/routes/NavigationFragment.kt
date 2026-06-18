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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Navigation tab — a Waze-style night driving dashboard over the shared
 * [MapsMapFragment] (dark + 3D, heading-follow). On-screen widgets:
 *   • top-right  — circular live SPEED gauge,
 *   • top-left   — ALERTS panel (placeholder until an alerts feed exists),
 *   • centre top — maneuver banner + "Where to?" search,
 *   • bottom bar — ETA (arrival clock) · AVG speed · DISTANCE left.
 *
 * Speed/avg come from GPS fixes; distance-left is great-circle to the
 * destination. Turn-by-turn maneuvers need the routing engine (next phase).
 */
class NavigationFragment : Fragment() {

    private val mapFragment = MapsMapFragment.newInstance(nav3d = true, fab = true)

    private var destLat: Double? = null
    private var destLon: Double? = null
    private var destLabel: String = ""
    private var speedSum = 0.0
    private var speedCount = 0

    private lateinit var maneuverText: TextView
    private lateinit var maneuverSub: TextView
    private lateinit var alertsText: TextView
    private lateinit var speedValue: TextView
    private lateinit var etaValue: TextView
    private lateinit var avgValue: TextView
    private lateinit var distValue: TextView
    private lateinit var destCard: MaterialCardView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val root = FrameLayout(ctx)

        val mapHost = FrameLayout(ctx).apply { id = View.generateViewId() }
        root.addView(mapHost, FrameLayout.LayoutParams(MATCH, MATCH))
        mapFragment.onUserLocation = { loc -> onFix(loc.hasSpeed(), if (loc.hasSpeed()) loc.speed * 3.6f else 0f, loc.latitude, loc.longitude) }
        if (childFragmentManager.findFragmentById(mapHost.id) == null) {
            childFragmentManager.beginTransaction().replace(mapHost.id, mapFragment).commit()
        }

        // ── Top stack: [alerts | speed-circle] · maneuver · search ───
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val topRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        // Alerts (top-left).
        val alertsCard = MaterialCardView(ctx).apply {
            radius = dpf(14f); cardElevation = dpf(6f); useCompatPadding = true; setCardBackgroundColor(COL_HUD_BG)
        }
        val alertsCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        alertsCol.addView(TextView(ctx).apply { text = "⚠ Alerts"; textSize = 12f; setTextColor(COL_HUD_SUB) })
        alertsText = TextView(ctx).apply { text = "No alerts"; textSize = 15f; setTextColor(COL_HUD_TEXT) }
        alertsCol.addView(alertsText)
        alertsCard.addView(alertsCol)
        topRow.addView(alertsCard, LinearLayout.LayoutParams(0, WRAP, 1f).apply { gravity = Gravity.TOP })

        // Speed circle (top-right).
        val speedCircle = MaterialCardView(ctx).apply {
            radius = dpf(42f); cardElevation = dpf(8f); useCompatPadding = true; setCardBackgroundColor(COL_HUD_BG)
        }
        val speedCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(84), dp(84))
        }
        speedValue = TextView(ctx).apply { text = "0"; textSize = 26f; setTextColor(COL_SPEED) }
        speedCol.addView(speedValue)
        speedCol.addView(TextView(ctx).apply { text = "km/h"; textSize = 10f; setTextColor(COL_HUD_SUB) })
        speedCircle.addView(speedCol)
        topRow.addView(speedCircle, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8); gravity = Gravity.TOP })
        top.addView(topRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // Maneuver banner.
        val banner = MaterialCardView(ctx).apply { radius = dpf(16f); cardElevation = dpf(6f); useCompatPadding = true; setCardBackgroundColor(COL_HUD_BG) }
        val bRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        bRow.addView(TextView(ctx).apply { text = "▲"; textSize = 26f; setTextColor(COL_TURN); setPadding(0, 0, dp(14), 0) })
        val bCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        maneuverText = TextView(ctx).apply { text = "Set a destination"; textSize = 18f; setTextColor(COL_HUD_TEXT) }
        maneuverSub = TextView(ctx).apply { text = "Tap “Where to?” to start"; textSize = 12f; setTextColor(COL_HUD_SUB) }
        bCol.addView(maneuverText); bCol.addView(maneuverSub)
        bRow.addView(bCol); banner.addView(bRow)
        top.addView(banner, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        // Search.
        destCard = SearchUi.searchCard(ctx, "Where to? Address or place…") { q -> setDestination(q) }
        top.addView(destCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.TOP; setMargins(dp(8), dp(8), dp(8), 0) })

        // ── Bottom bar: ETA · AVG · DIST ─────────────────────────────
        val hud = MaterialCardView(ctx).apply { radius = dpf(20f); cardElevation = dpf(10f); useCompatPadding = true; setCardBackgroundColor(COL_HUD_BG) }
        val hudRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        etaValue = metricValue(ctx); avgValue = metricValue(ctx); distValue = metricValue(ctx)
        hudRow.addView(metricCol(ctx, "ETA", etaValue.also { it.text = "—" }))
        hudRow.addView(metricCol(ctx, "AVG", avgValue.also { it.text = "— km/h" }))
        hudRow.addView(metricCol(ctx, "LEFT", distValue.also { it.text = "—" }))
        hud.addView(hudRow)
        root.addView(hud, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.BOTTOM; setMargins(dp(8), 0, dp(8), dp(8)) })

        return root
    }

    private fun metricValue(ctx: android.content.Context) = TextView(ctx).apply { textSize = 18f; setTextColor(COL_HUD_TEXT) }
    private fun metricCol(ctx: android.content.Context, label: String, value: TextView): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(value)
        col.addView(TextView(ctx).apply { text = label; textSize = 11f; setTextColor(COL_HUD_SUB) })
        col.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        return col
    }

    private fun onFix(hasSpeed: Boolean, kmh: Float, lat: Double, lon: Double) {
        speedValue.text = if (hasSpeed) "%.0f".format(kmh) else "0"
        if (hasSpeed && kmh > 0.5f) { speedSum += kmh; speedCount++ }
        val avg = if (speedCount > 0) speedSum / speedCount else 0.0
        avgValue.text = if (speedCount > 0) "%.0f km/h".format(avg) else "— km/h"
        val dLat = destLat; val dLon = destLon
        if (dLat != null && dLon != null) {
            val km = haversineKm(lat, lon, dLat, dLon)
            distValue.text = if (km < 1.0) "${(km * 1000).roundToInt()} m" else "%.1f km".format(km)
            val useSpeed = if (avg > 3.0) avg else kmh.toDouble()
            etaValue.text = if (useSpeed > 3.0) {
                val mins = km / useSpeed * 60.0
                SimpleDateFormat("HH:mm", Locale.US).format(Date(System.currentTimeMillis() + (mins * 60_000).toLong()))
            } else "—"
        }
    }

    private fun setDestination(query: String) {
        if (query.isBlank()) return
        val ctx = context ?: return
        val center = mapFragment.centerTarget()
        Thread {
            val hits = MapsProviderClient.forwardSearch(ctx, query, center?.first ?: 0.0, center?.second ?: 0.0)
            ui {
                if (hits.isEmpty()) { Toast.makeText(ctx, "No match for \"$query\"", Toast.LENGTH_SHORT).show(); return@ui }
                SearchUi.chooseResult(ctx, hits) { hit ->
                    destLat = hit.lat; destLon = hit.lon; destLabel = hit.title
                    speedSum = 0.0; speedCount = 0
                    SearchUi.field(destCard).setText(hit.title)
                    maneuverText.text = "Head to $destLabel"
                    maneuverSub.text = "Driving · follow the route"
                    mapFragment.setPins(listOf(MapsMapFragment.Pin(hit.lat, hit.lon, MapsMapFragment.COLOR_RESULT)))
                    mapFragment.recenterOnUser()
                }
            }
        }.start()
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun ui(block: () -> Unit) { if (!isAdded) return; requireActivity().runOnUiThread { if (isAdded) block() } }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val COL_HUD_BG   = 0xFF1A1F2B.toInt()
        const val COL_HUD_TEXT = 0xFFFFFFFF.toInt()
        const val COL_HUD_SUB  = 0xFFA9B0BD.toInt()
        const val COL_SPEED    = 0xFF34C759.toInt()
        const val COL_TURN     = 0xFF4DA3FF.toInt()
    }
}
