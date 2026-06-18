package com.diegonmarcos.cloudnav.maps

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Shared interactive MapLibre map — embedded by Routes / Navigation / Places
 * and the day-detail view. Data-driven raster basemaps ([MapStyles]); a
 * Map-switcher FAB (under the locate-me FAB) cycles Standard → Dark →
 * Satellite at runtime.
 *
 *   • [onMapReady]      fired once the GL style is loaded.
 *   • [setPins]/[clearPins]  coloured POI dots.
 *   • [recenterOnUser]  one-shot fused-location fix → camera + blue dot.
 *   • [recenter]/[fitTo]/[centerTarget]/[visibleBounds]  camera helpers.
 *   • ARG_NAV3D  start tilted (Navigation's 3D view).
 *   • ARG_STYLE  initial style key (defaults dark for nav3d, else light).
 *   • ARG_FAB    show the locate-me + switcher FABs (default true).
 */
class MapsMapFragment : Fragment() {

    /** One coloured dot to render. [color] is a CSS hex string. */
    data class Pin(val lat: Double, val lon: Double, val color: String = COLOR_RESULT)

    var onMapReady: ((MapLibreMap) -> Unit)? = null

    /** Fired on every successful my-location fix. Navigation drives its HUD. */
    var onUserLocation: ((Location) -> Unit)? = null

    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var styleReady = false

    private var pins: List<Pin> = emptyList()
    private var me: LatLng? = null
    private lateinit var styleKey: String

    private val nav3d: Boolean get() = arguments?.getBoolean(ARG_NAV3D, false) ?: false
    private val showFab: Boolean get() = arguments?.getBoolean(ARG_FAB, true) ?: true

    private val locPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) fetchAndCenter()
            else toast("Location permission denied")
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        MapLibre.getInstance(ctx)   // idempotent one-shot init.

        styleKey = arguments?.getString(ARG_STYLE) ?: if (nav3d) "dark" else "light"

        val root = FrameLayout(ctx).apply { layoutParams = ViewGroup.LayoutParams(MATCH, MATCH) }

        val prefs = MapsTrackerPrefs(ctx)
        val initialLat = if (prefs.hasLastFix()) prefs.lastFixLat else 20.0
        val initialLon = if (prefs.hasLastFix()) prefs.lastFixLon else 0.0
        val initialZoom = if (prefs.hasLastFix()) 14.0 else 2.0

        val mv = MapView(ctx).apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH) }
        mapView = mv
        mv.onCreate(s)

        mv.getMapAsync { m ->
            map = m
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(initialLat, initialLon))
                .zoom(initialZoom)
                .tilt(if (nav3d) NAV_TILT else 0.0)
                .build()
            applyStyle(styleKey, firstLoad = true)
        }
        root.addView(mv)

        if (showFab) {
            // Map-style switcher (small, on top).
            val switchFab = FloatingActionButton(ctx).apply {
                setImageResource(R.drawable.ic_map_layers)
                contentDescription = "Switch map style"
                size = FloatingActionButton.SIZE_MINI
                layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END)
                    .apply { val m = dp(16); setMargins(m, m, m, m + dp(140)) }
                setOnClickListener { cycleStyle() }
            }
            // Locate-me (below the switcher).
            val locFab = FloatingActionButton(ctx).apply {
                setImageResource(R.drawable.ic_my_location)
                contentDescription = "Find my location"
                size = FloatingActionButton.SIZE_NORMAL
                layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END)
                    .apply { val m = dp(16); setMargins(m, m, m, m + dp(72)) }
                setOnClickListener { recenterOnUser() }
            }
            root.addView(locFab)
            root.addView(switchFab)
        }
        return root
    }

    // ── public API ───────────────────────────────────────────────────
    fun setPins(newPins: List<Pin>) { pins = newPins; pushPins() }
    fun clearPins() = setPins(emptyList())

    fun centerTarget(): Pair<Double, Double>? =
        map?.cameraPosition?.target?.let { it.latitude to it.longitude }

    fun visibleBounds(): DoubleArray? {
        val b = map?.projection?.visibleRegion?.latLngBounds ?: return null
        return doubleArrayOf(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
    }

    fun recenter(lat: Double, lon: Double, zoom: Double = 15.0) {
        map?.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom)
                    .tilt(if (nav3d) NAV_TILT else 0.0).build()
            )
        )
    }

    fun fitTo(pts: List<Pin>) {
        val all = (pts.map { LatLng(it.lat, it.lon) } + listOfNotNull(me))
        if (all.isEmpty()) return
        if (all.size == 1) { recenter(all[0].latitude, all[0].longitude, 15.0); return }
        val bounds = LatLngBounds.Builder().apply { all.forEach { include(it) } }.build()
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(64)))
    }

    fun recenterOnUser() {
        val ctx = context ?: return
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) fetchAndCenter() else locPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // ── style switching ──────────────────────────────────────────────
    private fun cycleStyle() {
        styleKey = MapStyles.next(styleKey)
        applyStyle(styleKey, firstLoad = false)
        toast("Map: ${MapStyles.get(styleKey).label}")
    }

    /** (Re)load the raster style + re-add our overlay sources/layers. */
    private fun applyStyle(key: String, firstLoad: Boolean) {
        val m = map ?: return
        styleReady = false
        m.setStyle(Style.Builder().fromJson(MapStyles.styleJson(MapStyles.get(key)))) { style ->
            style.addSource(GeoJsonSource(SRC_PINS, featureCollection(pins)))
            style.addLayer(
                CircleLayer(LYR_PINS, SRC_PINS).withProperties(
                    PropertyFactory.circleColor(Expression.get("color")),
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(2f),
                )
            )
            style.addSource(GeoJsonSource(SRC_ME, featureCollection(emptyList())))
            style.addLayer(
                CircleLayer(LYR_ME, SRC_ME).withProperties(
                    PropertyFactory.circleColor(COLOR_ME),
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(3f),
                )
            )
            styleReady = true
            pushMe()
            if (firstLoad) onMapReady?.invoke(m)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchAndCenter() {
        val ctx = context ?: return
        val fused = LocationServices.getFusedLocationProviderClient(ctx)
        fused.lastLocation.addOnSuccessListener { last ->
            if (last != null) onLoc(last)
            else fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { cur -> if (cur != null) onLoc(cur) else toast("No location fix yet") }
                .addOnFailureListener { toast("Location unavailable") }
        }.addOnFailureListener { toast("Location unavailable") }
    }

    private fun onLoc(loc: Location) {
        me = LatLng(loc.latitude, loc.longitude)
        pushMe()
        onUserLocation?.invoke(loc)
        map?.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(loc.latitude, loc.longitude))
                    .zoom(if (nav3d) NAV_ZOOM else 16.0)
                    .tilt(if (nav3d) NAV_TILT else 0.0)
                    .bearing(if (nav3d && loc.hasBearing()) loc.bearing.toDouble() else 0.0)
                    .build()
            )
        )
    }

    // ── GeoJSON plumbing ─────────────────────────────────────────────
    private fun pushPins() {
        if (!styleReady) return
        map?.style?.getSourceAs<GeoJsonSource>(SRC_PINS)?.setGeoJson(featureCollection(pins))
    }

    private fun pushMe() {
        if (!styleReady) return
        val list = me?.let { listOf(Pin(it.latitude, it.longitude, COLOR_ME)) } ?: emptyList()
        map?.style?.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(featureCollection(list))
    }

    private fun featureCollection(items: List<Pin>): String {
        val feats = items.joinToString(",") { p ->
            """{"type":"Feature","properties":{"color":"${p.color}"},""" +
                """"geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]}}"""
        }
        return """{"type":"FeatureCollection","features":[$feats]}"""
    }

    private fun toast(msg: String) { context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() } }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onStart()   { super.onStart();   mapView?.onStart() }
    override fun onResume()  { super.onResume();  mapView?.onResume() }
    override fun onPause()   { mapView?.onPause();  super.onPause() }
    override fun onStop()    { mapView?.onStop();   super.onStop() }
    override fun onLowMemory(){ super.onLowMemory(); mapView?.onLowMemory() }
    override fun onDestroyView() {
        mapView?.onDestroy(); mapView = null; map = null; styleReady = false
        super.onDestroyView()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); mapView?.onSaveInstanceState(outState)
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT

        const val ARG_NAV3D = "nav3d"
        const val ARG_FAB   = "fab"
        const val ARG_STYLE = "style"

        const val COLOR_RESULT = "#D93025"
        const val COLOR_PLACE  = "#1A73E8"
        const val COLOR_DAY    = "#0B8043"
        const val COLOR_ME     = "#1A73E8"

        private const val SRC_PINS = "pins-src"
        private const val LYR_PINS = "pins-layer"
        private const val SRC_ME   = "me-src"
        private const val LYR_ME   = "me-layer"

        private const val NAV_TILT = 55.0
        private const val NAV_ZOOM = 18.0

        fun newInstance(nav3d: Boolean = false, fab: Boolean = true, style: String? = null) =
            MapsMapFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_NAV3D, nav3d)
                    putBoolean(ARG_FAB, fab)
                    if (style != null) putString(ARG_STYLE, style)
                }
            }
    }
}
