package com.diegonmarcos.cloudnav.routes

import android.content.Context
import android.location.Location
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.CockpitMode
import com.diegonmarcos.cloudnav.NavConfig
import com.diegonmarcos.cloudnav.SearchUi
import com.diegonmarcos.cloudnav.cockpit.CockpitGauges
import com.diegonmarcos.cloudnav.cockpit.CockpitInstrument
import com.diegonmarcos.cloudnav.cockpit.CockpitSensors
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
 * Navigation tab — a multi-transport cockpit over the shared [MapsMapFragment].
 * A mode switcher (Bike · Car · Boat · Airplane, data-driven from
 * build.json::ui.cockpit_modes → [NavConfig.cockpitModes]) retunes both the map
 * camera/basemap and the live instrument panel. Each mode declares its own gauge
 * cluster; gauges are fed from GPS fixes + [CockpitSensors] (heading, attitude,
 * barometric altitude/vertical-speed). No mocks — a gauge whose sensor is absent
 * simply reads its GPS fallback or stays blank.
 */
class NavigationFragment : Fragment() {

    private val modes = NavConfig.cockpitModes
    private lateinit var mode: CockpitMode
    private lateinit var mapFragment: MapsMapFragment
    private var sensors: CockpitSensors? = null

    private lateinit var switcherRow: LinearLayout
    private lateinit var cluster: LinearLayout
    private lateinit var maneuverText: TextView
    private lateinit var maneuverSub: TextView
    private lateinit var destCard: MaterialCardView
    private val chips = HashMap<String, MaterialCardView>()
    private val gauges = HashMap<String, CockpitInstrument>()

    private var destLat: Double? = null
    private var destLon: Double? = null
    private var destLabel: String = ""
    private var speedSum = 0.0
    private var speedCount = 0
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastAlt = Double.NaN

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        mode = modes.firstOrNull { it.id == savedMode(ctx) }
            ?: modes.firstOrNull { it.id == NavConfig.defaultCockpitMode }
            ?: modes.firstOrNull()
            ?: error("No cockpit_modes configured in build.json")

        val root = FrameLayout(ctx)

        // ── Map (nav3d so tilt/heading-follow apply; mode reconfigures camera).
        mapFragment = MapsMapFragment.newInstance(nav3d = true, fab = true, style = mode.style, autoLocate = true)
        val mapHost = FrameLayout(ctx).apply { id = View.generateViewId() }
        root.addView(mapHost, FrameLayout.LayoutParams(MATCH, MATCH))
        mapFragment.onUserLocation = { loc -> onFix(loc) }
        mapFragment.onMapReady = { _ -> mapFragment.applyNavMode(mode.zoom, mode.tilt, mode.style, mode.followBearing) }
        if (childFragmentManager.findFragmentById(mapHost.id) == null) {
            childFragmentManager.beginTransaction().replace(mapHost.id, mapFragment).commit()
        }

        // ── Top stack: mode switcher · search · maneuver banner ───────────
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        switcherRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        modes.forEach { m ->
            val chip = modeChip(ctx, m)
            chips[m.id] = chip
            switcherRow.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
        val switcherScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false; addView(switcherRow)
        }
        top.addView(switcherScroll, LinearLayout.LayoutParams(MATCH, WRAP))

        destCard = SearchUi.searchCard(ctx, "Where to? Address or place…") { q -> setDestination(q) }
        top.addView(destCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        val banner = MaterialCardView(ctx).apply { radius = dpf(16f); cardElevation = dpf(6f); useCompatPadding = true; setCardBackgroundColor(CockpitGauges.BG) }
        val bRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)) }
        val bIcon = TextView(ctx).apply { text = "▲"; textSize = 24f; setPadding(0, 0, dp(14), 0) }
        val bCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        maneuverText = TextView(ctx).apply { text = "Set a destination"; textSize = 17f; setTextColor(CockpitGauges.TEXT) }
        maneuverSub = TextView(ctx).apply { text = "Pick a mode, then “Where to?”"; textSize = 12f; setTextColor(CockpitGauges.SUB) }
        bIcon.setTextColor(mode.accent)
        bCol.addView(maneuverText); bCol.addView(maneuverSub)
        bRow.addView(bIcon); bRow.addView(bCol); banner.addView(bRow)
        top.addView(banner, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.TOP; setMargins(dp(8), dp(8), dp(8), 0) })

        // ── Bottom instrument cluster (data-driven per mode) ──────────────
        cluster = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val clusterCard = MaterialCardView(ctx).apply {
            radius = dpf(20f); cardElevation = dpf(10f); useCompatPadding = true
            setCardBackgroundColor(0xFF0A0D14.toInt())
        }
        clusterCard.addView(cluster, FrameLayout.LayoutParams(MATCH, dp(150)).apply {})
        root.addView(clusterCard, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.BOTTOM; setMargins(dp(8), 0, dp(8), dp(8)) })

        buildCluster()
        highlightChip()
        return root
    }

    override fun onResume() {
        super.onResume()
        sensors = CockpitSensors(requireContext()).also {
            it.onHeading = { deg -> gauges["heading"]?.onHeading(deg) }
            it.onAttitude = { p, r -> gauges["attitude"]?.onAttitude(p, r) }
            it.onBaroAltitude = { m, v ->
                gauges["altitude"]?.onAltitude(CockpitGauges.metersTo(mode.altUnit, m.toDouble()))
                gauges["vspeed"]?.onVerticalMps(v.toDouble())
            }
            it.start()
        }
    }

    override fun onPause() { sensors?.stop(); sensors = null; super.onPause() }

    // ── mode switching ───────────────────────────────────────────────────
    private fun modeChip(ctx: Context, m: CockpitMode): MaterialCardView {
        val card = MaterialCardView(ctx).apply { radius = dpf(18f); cardElevation = dpf(4f); useCompatPadding = true }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        row.addView(TextView(ctx).apply { text = m.emoji; textSize = 18f; setPadding(0, 0, dp(6), 0) })
        row.addView(TextView(ctx).apply { text = m.label; textSize = 14f; setTextColor(CockpitGauges.TEXT) })
        card.addView(row)
        card.setOnClickListener { if (m.id != mode.id) switchMode(m) }
        return card
    }

    private fun highlightChip() {
        chips.forEach { (id, card) ->
            val on = id == mode.id
            card.setCardBackgroundColor(if (on) mode.accent else CockpitGauges.BG)
            (card.getChildAt(0) as LinearLayout).let { row ->
                (row.getChildAt(1) as TextView).setTextColor(if (on) 0xFF0A0D14.toInt() else CockpitGauges.TEXT)
            }
        }
    }

    private fun switchMode(m: CockpitMode) {
        mode = m
        saveMode(requireContext(), m.id)
        speedSum = 0.0; speedCount = 0
        highlightChip()
        buildCluster()
        mapFragment.applyNavMode(m.zoom, m.tilt, m.style, m.followBearing)
    }

    private fun buildCluster() {
        cluster.removeAllViews()
        gauges.clear()
        mode.gauges.forEach { id ->
            val inst = CockpitGauges.create(requireContext(), id, mode)
            gauges[id] = inst
            cluster.addView(inst.view, LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
    }

    // ── live data ────────────────────────────────────────────────────────
    private fun onFix(loc: Location) {
        val kmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        val sUnit = CockpitGauges.speedUnitLabel(mode.speedUnit)
        gauges["speed"]?.onSpeed(CockpitGauges.kmhTo(mode.speedUnit, kmh), sUnit)

        if (kmh > 0.5) { speedSum += kmh; speedCount++ }
        val avgKmh = if (speedCount > 0) speedSum / speedCount else 0.0
        gauges["avg"]?.onMetric(if (speedCount > 0) "%.0f %s".format(CockpitGauges.kmhTo(mode.speedUnit, avgKmh), sUnit) else "—")

        if (loc.hasBearing()) gauges["track"]?.onMetric("%03d°".format(loc.bearing.roundToInt() % 360))
        gauges["position"]?.onMetric(fmtCoord(loc.latitude, "N", "S"), fmtCoord(loc.longitude, "E", "W"))

        // Altitude: barometer feeds it in onResume; GPS is the fallback.
        if (loc.hasAltitude() && sensors?.hasBarometer != true) {
            gauges["altitude"]?.onAltitude(CockpitGauges.metersTo(mode.altUnit, loc.altitude))
        }

        // Grade (%) from consecutive fixes: rise / horizontal run.
        if (!lastLat.isNaN() && loc.hasAltitude() && !lastAlt.isNaN()) {
            val runM = haversineKm(lastLat, lastLon, loc.latitude, loc.longitude) * 1000.0
            if (runM > 5.0) {
                val grade = (loc.altitude - lastAlt) / runM * 100.0
                gauges["grade"]?.onMetric("%+.0f%%".format(grade))
            }
        }
        lastLat = loc.latitude; lastLon = loc.longitude
        if (loc.hasAltitude()) lastAlt = loc.altitude

        // Distance + ETA to destination.
        val dLat = destLat; val dLon = destLon
        if (dLat != null && dLon != null) {
            val km = haversineKm(loc.latitude, loc.longitude, dLat, dLon)
            gauges["dist"]?.onMetric(if (km < 1.0) "${(km * 1000).roundToInt()} m" else "%.1f km".format(km))
            val useSpeed = if (avgKmh > 3.0) avgKmh else kmh
            gauges["eta"]?.onMetric(
                if (useSpeed > 3.0) {
                    val mins = km / useSpeed * 60.0
                    SimpleDateFormat("HH:mm", Locale.US).format(Date(System.currentTimeMillis() + (mins * 60_000).toLong()))
                } else "—"
            )
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
                    SearchUi.field(destCard).setText(hit.title)
                    maneuverText.text = "Head to $destLabel"
                    maneuverSub.text = "${mode.emoji} ${mode.label} · follow the route"
                    mapFragment.setPins(listOf(MapsMapFragment.Pin(hit.lat, hit.lon, MapsMapFragment.COLOR_RESULT)))
                    mapFragment.recenterOnUser()
                }
            }
        }.start()
    }

    private fun fmtCoord(v: Double, pos: String, neg: String): String =
        "%.4f°%s".format(kotlin.math.abs(v), if (v >= 0) pos else neg)

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun savedMode(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)
    private fun saveMode(ctx: Context, id: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, id).apply()

    private fun ui(block: () -> Unit) { if (!isAdded) return; requireActivity().runOnUiThread { if (isAdded) block() } }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val PREFS = "cloud_nav_cockpit"
        const val KEY_MODE = "mode"
    }
}
