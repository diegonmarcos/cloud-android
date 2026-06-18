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
import android.widget.TextView
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
 * Shared interactive MapLibre map — the single map widget embedded by the
 * Routes / Navigation / Places tabs and the day-detail view. Light raster
 * basemap (OSM). Provides a small public API so the host fragment can drop
 * pins, recenter on the user, and (for Navigation) tilt into a 3D driving
 * view.
 *
 *   • [onMapReady]      callback fired once the GL style is loaded.
 *   • [setPins]/[clearPins]  drop / clear coloured POI dots.
 *   • [recenterOnUser]  one-shot fused-location fix → animate camera + blue
 *                       "you are here" dot. Requests FINE location if needed.
 *   • [recenter]/[fitTo]  camera helpers.
 *   • ARG_NAV3D         start tilted (Navigation's 3D racing-car view).
 *   • ARG_FAB           show the bottom-right my-location FAB (default true).
 *
 * Pins are rendered as a single GeoJSON source + CircleLayer with a
 * per-feature `color` property (guaranteed-present core API — no glyphs /
 * marker bitmaps / extra plugin dependency).
 */
class MapsMapFragment : Fragment() {

    /** One coloured dot to render. [color] is a CSS hex string. */
    data class Pin(val lat: Double, val lon: Double, val color: String = COLOR_RESULT)

    var onMapReady: ((MapLibreMap) -> Unit)? = null

    /** Fired on every successful my-location fix (FAB / [recenterOnUser]).
     *  Navigation uses it to drive the speed HUD. */
    var onUserLocation: ((Location) -> Unit)? = null

    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var styleReady = false

    private var pins: List<Pin> = emptyList()
    private var me: LatLng? = null

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

        val root = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

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
            m.setStyle(Style.Builder().fromJson(LIGHT_RASTER_STYLE)) { style ->
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
                pushPins()
                pushMe()
                onMapReady?.invoke(m)
            }
        }
        root.addView(mv)

        if (showFab) {
            val fab = FloatingActionButton(ctx).apply {
                setImageResource(R.drawable.ic_my_location)
                contentDescription = "Find my location"
                size = FloatingActionButton.SIZE_NORMAL
                layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END)
                    .apply {
                        val m = dp(16)
                        setMargins(m, m, m, m + dp(72))   // clear the routing/place panels.
                    }
                setOnClickListener { recenterOnUser() }
            }
            root.addView(fab)
        }
        return root
    }

    // ── public API ───────────────────────────────────────────────────
    fun setPins(newPins: List<Pin>) { pins = newPins; pushPins() }
    fun clearPins() = setPins(emptyList())

    /** Current camera centre as (lat, lon), or null if the map isn't ready.
     *  Exposed MapLibre-free so app-module callers can bias a search toward
     *  the viewport without depending on the MapLibre SDK. */
    fun centerTarget(): Pair<Double, Double>? =
        map?.cameraPosition?.target?.let { it.latitude to it.longitude }

    /** Current viewport as [south, west, north, east], or null if not ready.
     *  Used by Places to scope an Overpass POI lookup to what's on screen. */
    fun visibleBounds(): DoubleArray? {
        val b = map?.projection?.visibleRegion?.latLngBounds ?: return null
        return doubleArrayOf(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
    }

    fun recenter(lat: Double, lon: Double, zoom: Double = 15.0) {
        map?.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(lat, lon))
                    .zoom(zoom)
                    .tilt(if (nav3d) NAV_TILT else 0.0)
                    .build()
            )
        )
    }

    /** Frame the camera around all [pts] (+ the user dot if present). */
    fun fitTo(pts: List<Pin>) {
        val all = (pts.map { LatLng(it.lat, it.lon) } + listOfNotNull(me))
        if (all.isEmpty()) return
        if (all.size == 1) { recenter(all[0].latitude, all[0].longitude, 15.0); return }
        val bounds = LatLngBounds.Builder().apply { all.forEach { include(it) } }.build()
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(64)))
    }

    /** One-shot fused-location fix → blue dot + camera. Asks for FINE
     *  permission first if it isn't granted yet. */
    fun recenterOnUser() {
        val ctx = context ?: return
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) fetchAndCenter() else locPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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

    private fun toast(msg: String) {
        context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ── MapView lifecycle (MapLibre isn't a LifecycleObserver) ────────
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

        const val COLOR_RESULT = "#D93025"   // red — search results
        const val COLOR_PLACE  = "#1A73E8"   // blue — POI categories
        const val COLOR_DAY    = "#0B8043"   // green — timeline day stops
        const val COLOR_ME     = "#1A73E8"   // blue — you-are-here dot

        private const val SRC_PINS = "pins-src"
        private const val LYR_PINS = "pins-layer"
        private const val SRC_ME   = "me-src"
        private const val LYR_ME   = "me-layer"

        private const val NAV_TILT = 55.0
        private const val NAV_ZOOM = 18.0

        // Light OSM raster basemap — inline so there's no dependency on a
        // remote style.json (only the tile server). Light grey background
        // so a tile gap reads as "land", not the old dark void.
        private val LIGHT_RASTER_STYLE = """
            {
              "version": 8,
              "name": "Cloud Nav · OSM raster (light)",
              "sources": {
                "osm": {
                  "type": "raster",
                  "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
                  "tileSize": 256,
                  "attribution": "© OpenStreetMap contributors",
                  "maxzoom": 19
                }
              },
              "layers": [
                { "id": "background", "type": "background", "paint": { "background-color": "#e8eef4" } },
                { "id": "osm",        "type": "raster",     "source": "osm" }
              ]
            }
        """

        fun newInstance(nav3d: Boolean = false, fab: Boolean = true) = MapsMapFragment().apply {
            arguments = Bundle().apply {
                putBoolean(ARG_NAV3D, nav3d)
                putBoolean(ARG_FAB, fab)
            }
        }
    }
}
