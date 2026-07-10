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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.bottomsheet.BottomSheetDialog
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
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

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
    /** Start at a whole-world camera instead of the user's last GPS fix —
     *  for global-overview screens (Explored). Without this, a device with
     *  tracker history opens the map zoomed ~14 into the user's own city and
     *  pins on other continents are simply off-screen ("empty map"), while a
     *  fresh device/emulator (no fix → world zoom) looks fine — the exact
     *  device-vs-CI split that hid the Explored bug. */
    private val worldView: Boolean get() = arguments?.getBoolean(ARG_WORLD_VIEW, false) ?: false
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

        // Every screen's raw style request (an explicit ARG_STYLE, else DARK —
        // Cloud Nav is a dark-mode app) resolves through the user's Basemap
        // choice: an explicit pin wins outright; otherwise the family preference
        // maps this screen's raster pick to its vector/hybrid equivalent
        // (dark → vector_dark by default).
        val rawStyle = arguments?.getString(ARG_STYLE) ?: "dark"
        styleKey = MapsBasemapPrefs(ctx).resolve(rawStyle)

        val root = FrameLayout(ctx).apply { layoutParams = ViewGroup.LayoutParams(MATCH, MATCH) }

        val prefs = MapsTrackerPrefs(ctx)
        val initialLat = if (worldView) 15.0 else if (prefs.hasLastFix()) prefs.lastFixLat else 20.0
        val initialLon = if (worldView) 0.0 else if (prefs.hasLastFix()) prefs.lastFixLon else 0.0
        val initialZoom = if (worldView) 0.8 else if (prefs.hasLastFix()) 14.0 else 2.0

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
    fun setPins(newPins: List<Pin>) {
        pins = newPins
        android.util.Log.i("MapPins", "setPins ${newPins.size} inst=${System.identityHashCode(this)} styleReady=$styleReady")
        pushPins()
    }
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
     * Test/diagnostic probe (MAIN THREAD only): first = feature count in the
     * pins GeoJSON source (data plumbed into the style), second = pin circles
     * actually RENDERED in the current viewport (GL really drew them). -1 =
     * map/style not ready yet. Backs the CI emulator render test
     * (ExploredRenderTest) — the machine check this screen never had while it
     * shipped broken four times on static review alone.
     */
    /** Test/diagnostic: render the current GL map (basemap + pins) to a Bitmap
     *  offscreen — works on a headless emulator where UiAutomation screenshots
     *  return null. Backs ExploredRenderTest's evidence PNG. */
    fun snapshot(cb: (android.graphics.Bitmap) -> Unit) {
        val m = map ?: return
        m.snapshot(cb)
    }

    fun pinProbe(): Pair<Int, Int> {
        val m = map ?: return -1 to -1
        val src = runCatching {
            m.style?.getSourceAs<GeoJsonSource>(SRC_PINS)
                ?.querySourceFeatures(null as Expression?)?.size ?: -1
        }.getOrDefault(-1)
        val mv = mapView ?: return src to -1
        if (mv.width == 0 || mv.height == 0) return src to -1
        val rendered = runCatching {
            m.queryRenderedFeatures(
                android.graphics.RectF(0f, 0f, mv.width.toFloat(), mv.height.toFloat()),
                LYR_PINS,
            ).size
        }.getOrDefault(-1)
        return src to rendered
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
        val m = map ?: return
        val all = (pts.map { LatLng(it.lat, it.lon) } + listOfNotNull(me))
        if (all.isEmpty()) return
        if (all.size == 1) { recenter(all[0].latitude, all[0].longitude, 15.0); return }
        // getCameraForLatLngBounds is NULLABLE — for very wide (multi-continent)
        // bounds it can fail, and the old newLatLngBounds animateCamera then
        // silently no-ops, leaving the camera wherever it was (e.g. zoomed into
        // the user's home city with every pin off-screen). Fall back to an
        // explicit centred world-ish view so the pins are ALWAYS on screen.
        runCatching {
            val bounds = LatLngBounds.Builder().apply { all.forEach { include(it) } }.build()
            val pad = dp(48)
            val cam = m.getCameraForLatLngBounds(bounds, intArrayOf(pad, pad, pad, pad))
            if (cam != null) {
                m.animateCamera(CameraUpdateFactory.newCameraPosition(cam))
            } else {
                val cLat = all.map { it.latitude }.average()
                val cLon = all.map { it.longitude }.average()
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(cLat, cLon), 1.0))
            }
        }.onFailure {
            val cLat = all.map { it.latitude }.average()
            val cLon = all.map { it.longitude }.average()
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(cLat, cLon), 1.0))
        }
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
    /** Tap the map-style FAB → a dark bottom sheet with a rich row per style
     *  (kind glyph + label + one-line description + a ✓ on the current one). */
    private fun showStyleMenu() {
        val ctx = context ?: return
        fun dpi(v: Int) = (v * resources.displayMetrics.density).toInt()
        val dialog = BottomSheetDialog(ctx)

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF141A25.toInt())
            setPadding(dpi(8), dpi(14), dpi(8), dpi(18))
        }
        // Grab handle + title.
        col.addView(View(ctx).apply {
            setBackgroundColor(0xFF3A4557.toInt())
            layoutParams = LinearLayout.LayoutParams(dpi(36), dpi(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dpi(12)
            }
        })
        col.addView(TextView(ctx).apply {
            text = "Map style"; textSize = 17f
            setTextColor(0xFFECEFF4.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dpi(12), 0, dpi(12), dpi(8))
        })

        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        MapStyles.order.forEach { key ->
            val s = MapStyles.get(key)
            val (glyph, kind) = when {
                s.hybrid -> "🛰️" to "Imagery + English labels"
                s.vectorStyleUrl != null -> "✨" to "Vector · English labels · sharp"
                else -> "🗺️" to "Raster tiles"
            }
            val selected = key == styleKey
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(14), dpi(12), dpi(14), dpi(12))
                setBackgroundColor(if (selected) 0xFF1F2A1F.toInt() else 0x00000000)
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    if (key != styleKey) {
                        styleKey = key
                        applyStyle(key, firstLoad = false)
                        toast("Map: ${s.label}")
                    }
                }
            }
            row.addView(TextView(ctx).apply { text = glyph; textSize = 22f; setPadding(0, 0, dpi(14), 0) })
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            texts.addView(TextView(ctx).apply {
                text = s.label; textSize = 16f
                setTextColor(if (selected) 0xFF4ADE80.toInt() else 0xFFECEFF4.toInt())
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
            texts.addView(TextView(ctx).apply {
                text = kind; textSize = 12f; setTextColor(0xFF9AA3B2.toInt())
            })
            row.addView(texts)
            if (selected) row.addView(TextView(ctx).apply { text = "✓"; textSize = 18f; setTextColor(0xFF4ADE80.toInt()) })
            list.addView(row)
        }
        col.addView(ScrollView(ctx).apply { addView(list) })
        dialog.setContentView(col)
        dialog.show()
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
        android.util.Log.i("MapPins", "onStyleLoaded pins=${pins.size} inst=${System.identityHashCode(this)} firstLoad=$firstLoad")
        style.addSource(GeoJsonSource(SRC_PINS, featureCollection(pins)))
        style.addLayer(
            CircleLayer(LYR_PINS, SRC_PINS).withProperties(
                // toColor: coerce the "color" STRING property to a colour —
                // a bare get() yields a string type the circle-color paint
                // won't accept, leaving pins uncoloured.
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
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

    /** A FeatureCollection holding one LineString. Typed MapLibre geojson —
     *  NOT a raw JSON string: under MapLibre 11.7 a String passed to
     *  GeoJsonSource/setGeoJson was NOT parsed into features (the source stayed
     *  empty → every pin invisible, `querySourceFeatures`=0). Root cause of the
     *  Explored "empty map" that survived six review-only fixes; only the CI
     *  emulator probe (src=0 with pins=228 confirmed delivered) pinned it down. */
    private fun routeFeatureCollection(): FeatureCollection {
        if (routeGeometry.size < 2) return FeatureCollection.fromFeatures(emptyList())
        val line = LineString.fromLngLats(routeGeometry.map { Point.fromLngLat(it[1], it[0]) })
        return FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(line)))
    }

    private fun featureCollection(items: List<Pin>): FeatureCollection =
        FeatureCollection.fromFeatures(
            items.map { p ->
                Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                    addStringProperty("color", p.color)
                    addStringProperty("title", p.title)
                    addStringProperty("id", p.id)
                }
            }
        )

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
        const val ARG_WORLD_VIEW = "world_view"

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

        fun newInstance(
            nav3d: Boolean = false,
            fab: Boolean = true,
            style: String? = null,
            autoLocate: Boolean = false,
            worldView: Boolean = false,
        ) =
            MapsMapFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_NAV3D, nav3d)
                    putBoolean(ARG_FAB, fab)
                    putBoolean(ARG_AUTOLOCATE, autoLocate)
                    putBoolean(ARG_WORLD_VIEW, worldView)
                    if (style != null) putString(ARG_STYLE, style)
                }
            }
    }
}
