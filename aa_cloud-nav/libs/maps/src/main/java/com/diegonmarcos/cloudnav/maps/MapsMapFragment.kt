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
import androidx.appcompat.app.AlertDialog
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
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
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

    /** One coloured dot to render. [color] is a CSS hex string. [title] is the
     *  always-on map label; [id] is echoed back by [onPinClick] on tap. */
    data class Pin(
        val lat: Double,
        val lon: Double,
        val color: String = COLOR_RESULT,
        val title: String = "",
        val id: String = "",
    )

    var onMapReady: ((MapLibreMap) -> Unit)? = null

    /** Fired on every successful my-location fix. Navigation drives its HUD. */
    var onUserLocation: ((Location) -> Unit)? = null

    /** Fired with a pin's [Pin.id] when its marker/label is tapped. */
    var onPinClick: ((String) -> Unit)? = null

    /** Fired with (lat, lon) on a long-press anywhere on the map. */
    var onMapLongClick: ((Double, Double) -> Unit)? = null

    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var styleReady = false

    private var pins: List<Pin> = emptyList()
    private var me: LatLng? = null
    private var routeGeometry: List<DoubleArray> = emptyList()  // [lat,lon] points
    private lateinit var styleKey: String

    private val nav3d: Boolean get() = arguments?.getBoolean(ARG_NAV3D, false) ?: false
    private val showFab: Boolean get() = arguments?.getBoolean(ARG_FAB, true) ?: true
    private val autoLocate: Boolean get() = arguments?.getBoolean(ARG_AUTOLOCATE, false) ?: false
    private var autoLocateDone = false

    // Cockpit-mode camera overrides (null → fall back to nav3d defaults). Set via [applyNavMode].
    private var modeZoom: Double? = null
    private var modeTilt: Double? = null
    private var modeFollowBearing: Boolean = true
    private val effZoom: Double get() = modeZoom ?: if (nav3d) NAV_ZOOM else 16.0
    private val effTilt: Double get() = modeTilt ?: if (nav3d) NAV_TILT else 0.0

    private val locPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) fetchAndCenter()
            else toast("Location permission denied")
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        MapLibre.getInstance(ctx)   // idempotent one-shot init.

        // Every screen's raw style request (an explicit ARG_STYLE, or the
        // nav3d-based default) resolves through the user's Basemap choice —
        // an explicit pin wins outright; otherwise the family preference maps
        // this screen's raster pick to its vector/hybrid equivalent.
        val rawStyle = arguments?.getString(ARG_STYLE) ?: if (nav3d) "dark" else "light"
        styleKey = MapsBasemapPrefs(ctx).resolve(rawStyle)

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
            // Native compass: appears when rotated, tap = snap back to north.
            m.uiSettings.isCompassEnabled = true
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(initialLat, initialLon))
                .zoom(initialZoom)
                .tilt(if (nav3d) NAV_TILT else 0.0)
                .build()
            applyStyle(styleKey, firstLoad = true)

            // Tap a pin → resolve its feature → echo the id back to the caller.
            m.addOnMapClickListener { latLng ->
                val cb = onPinClick ?: return@addOnMapClickListener false
                val pt = m.projection.toScreenLocation(latLng)
                val feats = m.queryRenderedFeatures(pt, LYR_PINS, LYR_PIN_LABELS)
                val id = feats.firstOrNull { it.hasProperty("id") }?.getStringProperty("id")
                if (!id.isNullOrEmpty()) { cb(id); true } else false
            }
            m.addOnMapLongClickListener { latLng ->
                val cb = onMapLongClick ?: return@addOnMapLongClickListener false
                cb(latLng.latitude, latLng.longitude); true
            }
        }
        root.addView(mv)

        if (showFab) {
            // Map-style switcher (top of the stack).
            val switchFab = FloatingActionButton(ctx).apply {
                setImageResource(R.drawable.ic_map_layers)
                contentDescription = "Switch map style"
                size = FloatingActionButton.SIZE_MINI
                layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END)
                    .apply { val m = dp(16); setMargins(m, m, m, m + dp(200)) }
                setOnClickListener { showStyleMenu() }
            }
            // Reset-to-north (between switcher and locate-me).
            val northFab = FloatingActionButton(ctx).apply {
                setImageResource(R.drawable.ic_compass_north)
                contentDescription = "Face north"
                size = FloatingActionButton.SIZE_MINI
                layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END)
                    .apply { val m = dp(16); setMargins(m, m, m, m + dp(140)) }
                setOnClickListener { resetNorth() }
            }
            // Locate-me (bottom of the stack).
            val locFab = FloatingActionButton(ctx).apply {
                setImageResource(R.drawable.ic_my_location)
                contentDescription = "Find my location"
                size = FloatingActionButton.SIZE_NORMAL
                layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END)
                    .apply { val m = dp(16); setMargins(m, m, m, m + dp(72)) }
                setOnClickListener { recenterOnUser() }
            }
            root.addView(locFab)
            root.addView(northFab)
            root.addView(switchFab)
        }
        return root
    }

    // ── public API ───────────────────────────────────────────────────
    fun setPins(newPins: List<Pin>) { pins = newPins; pushPins() }
    fun clearPins() = setPins(emptyList())

    fun centerTarget(): Pair<Double, Double>? =
        map?.cameraPosition?.target?.let { it.latitude to it.longitude }

    /** Last my-location fix, or null. The Routes "Your location" origin. */
    fun myLatLon(): Pair<Double, Double>? = me?.let { it.latitude to it.longitude }

    /** Draw a route line through [geometry] ([lat,lon] points). Empty clears. */
    fun setRoute(geometry: List<DoubleArray>) { routeGeometry = geometry; pushRoute() }
    fun clearRoute() = setRoute(emptyList())

    fun visibleBounds(): DoubleArray? {
        val b = map?.projection?.visibleRegion?.latLngBounds ?: return null
        return doubleArrayOf(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
    }

    fun recenter(lat: Double, lon: Double, zoom: Double = 15.0) {
        map?.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom)
                    .tilt(effTilt).build()
            )
        )
    }

    /** Rotate the camera back to north-up (bearing 0), keeping target/zoom. */
    fun resetNorth() {
        map?.animateCamera(CameraUpdateFactory.bearingTo(0.0))
    }

    /**
     * Retune the live camera + basemap for a cockpit mode (Navigation tab).
     * Switches the raster style if it changed, updates zoom/tilt/heading-follow,
     * and re-frames on the last known position (or fetches one).
     */
    fun applyNavMode(zoom: Double, tilt: Double, rawStyleKey: String, followBearing: Boolean) {
        modeZoom = zoom; modeTilt = tilt; modeFollowBearing = followBearing
        val ctx = context
        val resolved = if (ctx != null) MapsBasemapPrefs(ctx).resolve(rawStyleKey) else rawStyleKey
        val m = map
        if (m == null) { this.styleKey = resolved; return }  // pre-map: onCreate reads styleKey
        if (resolved != this.styleKey) {
            this.styleKey = resolved
            applyStyle(resolved, firstLoad = false)
        }
        val here = me
        if (here != null) {
            m.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(here).zoom(zoom).tilt(tilt)
                        .bearing(if (followBearing) m.cameraPosition.bearing else 0.0).build()
                )
            )
        } else recenterOnUser()
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
    /** Tap the map-style FAB → a picker of every [MapStyles.order] entry
     *  (label shown, current one pre-selected) — jump straight to one instead
     *  of blindly cycling through them one at a time. */
    private fun showStyleMenu() {
        val ctx = context ?: return
        val keys = MapStyles.order
        val labels = keys.map { MapStyles.get(it).label }.toTypedArray()
        val current = keys.indexOf(styleKey).let { if (it < 0) 0 else it }
        AlertDialog.Builder(ctx)
            .setTitle("Map style")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                val key = keys[which]
                if (key != styleKey) {
                    styleKey = key
                    applyStyle(key, firstLoad = false)
                    toast("Map: ${MapStyles.get(key).label}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * (Re)load the basemap + re-add our overlay sources/layers.
     *   • raster — inline JSON, synchronous.
     *   • vector — fetches + localizes a remote GL style off-thread ([VectorStyleLoader]).
     *   • hybrid — fetches a vector overlay and composites it over a raster
     *     style's imagery off-thread ([VectorStyleLoader.buildHybrid]).
     * Vector/hybrid network failure falls back to [Style.rasterFallback]
     * (toasted — never a blank map); [firstLoad]'s onMapReady/auto-locate only
     * fires once the eventual style actually applies.
     */
    private fun applyStyle(key: String, firstLoad: Boolean) {
        val m = map ?: return
        styleReady = false
        val s = MapStyles.get(key)
        when {
            s.hybrid -> {
                val base = MapStyles.get(s.baseStyleKey ?: "satellite")
                Thread {
                    val json = s.vectorOverlayUrl?.let { VectorStyleLoader.buildHybrid(base, it, s.labelFieldPref) }
                    ui { applyRemoteResult(json, s, m, firstLoad) }
                }.start()
            }
            s.vectorStyleUrl != null -> {
                Thread {
                    val json = VectorStyleLoader.loadLocalized(s.vectorStyleUrl, s.labelFieldPref)
                    ui { applyRemoteResult(json, s, m, firstLoad) }
                }.start()
            }
            else -> m.setStyle(Style.Builder().fromJson(MapStyles.styleJson(s))) { style -> onStyleLoaded(style, m, firstLoad) }
        }
    }

    /** Applies a fetched vector/hybrid style JSON, or falls back to raster on failure. */
    private fun applyRemoteResult(json: String?, s: MapStyles.Style, m: MapLibreMap, firstLoad: Boolean) {
        if (json == null) {
            val fallback = s.rasterFallback ?: if (nav3d) "dark" else "light"
            toast("${s.label} unavailable — using raster")
            styleKey = fallback
            applyStyle(fallback, firstLoad)
            return
        }
        map?.setStyle(Style.Builder().fromJson(json)) { style -> onStyleLoaded(style, m, firstLoad) }
    }

    /** Re-add pins/route/me overlays after any style (re)load, raster or vector. */
    private fun onStyleLoaded(style: Style, m: MapLibreMap, firstLoad: Boolean) {
        // Route line FIRST so pins/me render on top of it.
        style.addSource(GeoJsonSource(SRC_ROUTE, routeFeatureCollection()))
        style.addLayer(
            LineLayer(LYR_ROUTE, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(COLOR_ROUTE),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        style.addSource(GeoJsonSource(SRC_PINS, featureCollection(pins)))
        style.addLayer(
            CircleLayer(LYR_PINS, SRC_PINS).withProperties(
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2.5f),
            )
        )
        // Always-on title label above each pin (the "balloon" text).
        style.addLayer(
            SymbolLayer(LYR_PIN_LABELS, SRC_PINS).withProperties(
                PropertyFactory.textField(Expression.get("title")),
                PropertyFactory.textSize(12f),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textHaloColor("#101418"),
                PropertyFactory.textHaloWidth(1.6f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, 0.9f)),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textOptional(true),
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
        pushRoute()
        if (firstLoad) {
            onMapReady?.invoke(m)
            // Auto-locate once on open (Routes) → drops the my-location dot
            // and fires onUserLocation so the route can auto-render.
            if (autoLocate && !autoLocateDone) { autoLocateDone = true; recenterOnUser() }
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
                    .zoom(effZoom)
                    .tilt(effTilt)
                    .bearing(if (modeFollowBearing && loc.hasBearing()) loc.bearing.toDouble() else 0.0)
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

    private fun pushRoute() {
        if (!styleReady) return
        map?.style?.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(routeFeatureCollection())
    }

    /** A FeatureCollection holding one LineString (coords are [lon,lat]). */
    private fun routeFeatureCollection(): String {
        if (routeGeometry.size < 2) return """{"type":"FeatureCollection","features":[]}"""
        val coords = routeGeometry.joinToString(",") { "[${it[1]},${it[0]}]" }
        return """{"type":"FeatureCollection","features":[""" +
            """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[$coords]}}]}"""
    }

    private fun featureCollection(items: List<Pin>): String {
        val features = org.json.JSONArray()
        items.forEach { p ->
            val props = org.json.JSONObject()
                .put("color", p.color).put("title", p.title).put("id", p.id)
            val geom = org.json.JSONObject().put("type", "Point")
                .put("coordinates", org.json.JSONArray().put(p.lon).put(p.lat))
            features.put(org.json.JSONObject().put("type", "Feature").put("properties", props).put("geometry", geom))
        }
        return org.json.JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }

    private fun toast(msg: String) { context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() } }
    private fun ui(block: () -> Unit) { if (!isAdded) return; activity?.runOnUiThread { if (isAdded) block() } }
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
        const val ARG_AUTOLOCATE = "autolocate"

        const val COLOR_RESULT = "#D93025"
        const val COLOR_PLACE  = "#1A73E8"
        const val COLOR_DAY    = "#0B8043"
        const val COLOR_ME     = "#1A73E8"

        private const val SRC_PINS  = "pins-src"
        private const val LYR_PINS  = "pins-layer"
        private const val LYR_PIN_LABELS = "pins-labels"
        private const val SRC_ME    = "me-src"
        private const val LYR_ME    = "me-layer"
        private const val SRC_ROUTE = "route-src"
        private const val LYR_ROUTE = "route-layer"
        private const val COLOR_ROUTE = "#1A73E8"

        private const val NAV_TILT = 55.0
        private const val NAV_ZOOM = 18.0

        fun newInstance(nav3d: Boolean = false, fab: Boolean = true, style: String? = null, autoLocate: Boolean = false) =
            MapsMapFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_NAV3D, nav3d)
                    putBoolean(ARG_FAB, fab)
                    putBoolean(ARG_AUTOLOCATE, autoLocate)
                    if (style != null) putString(ARG_STYLE, style)
                }
            }
    }
}
