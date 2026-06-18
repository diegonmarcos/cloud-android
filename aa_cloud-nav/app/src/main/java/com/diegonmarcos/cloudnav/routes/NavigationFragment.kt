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
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Navigation tab — a Waze-style night driving view. The shared
 * [MapsMapFragment] runs in dark + 3D mode (tilted, heading-follow). On top:
 *   • a maneuver banner (turn arrow + next instruction + distance-to-next),
 *   • a "Where to?" search pill that geocodes the destination,
 *   • a bottom driver bar with live speed, ETA (arrival clock), and remaining
 *     distance (great-circle to destination, updated each GPS fix).
 *
 * Turn-by-turn maneuver decoding + voice needs a routing engine (next phase);
 * the banner shows the live bearing/heading cue + straight-line remaining
 * distance until then.
 */
class NavigationFragment : Fragment() {

    private val mapFragment = MapsMapFragment.newInstance(nav3d = true, fab = true)

    private var destLat: Double? = null
    private var destLon: Double? = null
    private var destLabel: String = ""

    private lateinit var maneuverText: TextView
    private lateinit var maneuverSub: TextView
    private lateinit var speedValue: TextView
    private lateinit var etaValue: TextView
    private lateinit var distValue: TextView
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
            speedValue.text = if (loc.hasSpeed()) "%.0f".format(kmh) else "0"
            updateRemaining(loc.latitude, loc.longitude, kmh)
        }
        if (childFragmentManager.findFragmentById(mapHost.id) == null) {
            childFragmentManager.beginTransaction().replace(mapHost.id, mapFragment).commit()
        }

        // ── Top: maneuver banner + search pill ───────────────────────
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val banner = MaterialCardView(ctx).apply {
            radius = dp(18f); cardElevation = dp(8f); useCompatPadding = true
            setCardBackgroundColor(COL_HUD_BG)
        }
        val bRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18f).toInt(), dp(14f).toInt(), dp(18f).toInt(), dp(14f).toInt())
        }
        bRow.addView(TextView(ctx).apply {
            text = "▲"
            textSize = 30f
            setTextColor(COL_TURN)
            setPadding(0, 0, dp(16f).toInt(), 0)
        })
        val bCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        maneuverText = TextView(ctx).apply {
            text = "Set a destination"
            textSize = 19f
            setTextColor(COL_HUD_TEXT)
        }
        maneuverSub = TextView(ctx).apply {
            text = "Tap “Where to?” to start"
            textSize = 13f
            setTextColor(COL_HUD_SUB)
        }
        bCol.addView(maneuverText)
        bCol.addView(maneuverSub)
        bRow.addView(bCol)
        banner.addView(bRow)
        top.addView(banner, LinearLayout.LayoutParams(MATCH, WRAP))

        destCard = SearchUi.searchCard(ctx, "Where to? Address or place…") { q -> setDestination(q) }
        top.addView(destCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6f).toInt() })

        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP
            setMargins(dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), 0)
        })

        // ── Bottom: Waze-style driver bar ────────────────────────────
        val hud = MaterialCardView(ctx).apply {
            radius = dp(22f); cardElevation = dp(10f); useCompatPadding = true
            setCardBackgroundColor(COL_HUD_BG)
        }
        val hudRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f).toInt(), dp(12f).toInt(), dp(20f).toInt(), dp(12f).toInt())
        }
        // Speed block.
        val speedCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        speedValue = TextView(ctx).apply { text = "0"; textSize = 34f; setTextColor(COL_SPEED) }
        speedCol.addView(speedValue)
        speedCol.addView(TextView(ctx).apply { text = "km/h"; textSize = 11f; setTextColor(COL_HUD_SUB) })
        hudRow.addView(speedCol)
        // ETA + remaining.
        val etaCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f).toInt(), 0, 0, 0)
        }
        etaValue = TextView(ctx).apply { text = "—"; textSize = 20f; setTextColor(COL_HUD_TEXT) }
        distValue = TextView(ctx).apply { text = "No destination"; textSize = 13f; setTextColor(COL_HUD_SUB) }
        etaCol.addView(etaValue)
        etaCol.addView(distValue)
        hudRow.addView(etaCol, LinearLayout.LayoutParams(0, WRAP, 1f))
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
                destLat = hit.lat; destLon = hit.lon; destLabel = hit.title
                SearchUi.field(destCard).setText(hit.title)
                maneuverText.text = "Head to $destLabel"
                maneuverSub.text = "Driving · follow the route"
                mapFragment.setPins(listOf(MapsMapFragment.Pin(hit.lat, hit.lon, MapsMapFragment.COLOR_RESULT)))
                mapFragment.recenterOnUser()   // snap to the 3D follow view.
            }
        }.start()
    }

    /** Update ETA + remaining distance from the latest fix to the destination
     *  (great-circle). Real road distance/time needs the routing engine. */
    private fun updateRemaining(lat: Double, lon: Double, kmh: Float) {
        val dLat = destLat; val dLon = destLon
        if (dLat == null || dLon == null) return
        val km = haversineKm(lat, lon, dLat, dLon)
        distValue.text = if (km < 1.0) "%.0f m left".format(km * 1000) else "%.1f km left".format(km)
        if (kmh > 3f) {
            val mins = (km / kmh * 60.0)
            val arrival = Date(System.currentTimeMillis() + (mins * 60_000).toLong())
            etaValue.text = SimpleDateFormat("HH:mm", Locale.US).format(arrival)
        } else {
            etaValue.text = "—"
        }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun ui(block: () -> Unit) {
        if (!isAdded) return
        requireActivity().runOnUiThread { if (isAdded) block() }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val COL_HUD_BG   = 0xFF1A1F2B.toInt()  // dark slate (night HUD)
        const val COL_HUD_TEXT = 0xFFFFFFFF.toInt()
        const val COL_HUD_SUB  = 0xFFA9B0BD.toInt()
        const val COL_SPEED    = 0xFF34C759.toInt()  // green speed
        const val COL_TURN     = 0xFF4DA3FF.toInt()  // blue turn arrow
    }
}
