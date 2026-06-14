package com.diegonmarcos.cloudnav.maps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Maps → Map page. MapLibre Native MapView centred on the user's most
 * recent GPS fix (or a neutral Atlantic centre on a fresh install).
 * The map style URL comes from MapLibre's public demo style — free,
 * no key, no signup. To swap to a richer style later (Stadia, OSM
 * Bright, custom) edit build.json::ui.maps_tile_style; for v1 we
 * hardcode the demo URL since it's a single field and switching is
 * easy.
 *
 * Push 3 adds: track polyline + Stop markers + tap-to-recenter.
 */
class MapsMapFragment : Fragment() {

    private var mapView: MapView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        // MapLibre needs a one-shot init before any MapView is inflated.
        // Idempotent — safe to call from every fragment that uses
        // MapLibre.
        MapLibre.getInstance(ctx)

        val root = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val prefs = MapsTrackerPrefs(ctx)
        val initialLat = if (prefs.hasLastFix()) prefs.lastFixLat else 0.0
        val initialLon = if (prefs.hasLastFix()) prefs.lastFixLon else 0.0
        val initialZoom = if (prefs.hasLastFix()) 14.0 else 2.0

        val mv = MapView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        mapView = mv
        mv.onCreate(savedInstanceStateOrNull())

        // Visible load-state badge — top-left corner so the user can
        // see whether the style fetch + first tile loads succeeded
        // without digging into logcat. Previous version relied on the
        // remote demotiles.maplibre.org style.json which was flaky on
        // some networks → user saw a blank screen with no signal.
        val statusBadge = TextView(ctx).apply {
            text = "Map · loading style…"
            setTextColor(0xFFFFFFFFL.toInt())
            setBackgroundColor(0xCC000000.toInt())
            val p = (6 * ctx.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.START,
            ).apply { setMargins(p, p, p, p) }
        }

        mv.getMapAsync { map ->
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(initialLat, initialLon))
                .zoom(initialZoom)
                .build()
            // Raster-tile style built inline — no dependency on a remote
            // style.json, only on the tile server itself. tile.openstreetmap.org
            // is fair-use OK at the personal-tracker volumes we generate.
            // Promote tileUrl + attribution to build.json::ui.maps_tile_*
            // when we add the Configs picker for tile sources.
            val styleJson = """
                {
                  "version": 8,
                  "name": "Cloud SuperApp · OSM raster",
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
                    { "id": "background", "type": "background", "paint": { "background-color": "#0a0814" } },
                    { "id": "osm",        "type": "raster",     "source": "osm" }
                  ]
                }
            """.trimIndent()
            map.setStyle(Style.Builder().fromJson(styleJson)) { _ ->
                statusBadge.text = "Map · style ready"
                // Auto-hide after a couple seconds so it doesn't clutter
                // the map permanently. If the style FAILED to load, the
                // callback never fires and the badge stays on "loading
                // style…" — itself a clear "tiles aren't loading" signal
                // the user can act on.
                statusBadge.postDelayed({ statusBadge.visibility = View.GONE }, 2500L)
            }
        }
        root.addView(mv)
        root.addView(statusBadge)

        // Tiny overlay caption so empty installs explain themselves.
        if (!prefs.hasLastFix()) {
            root.addView(TextView(ctx).apply {
                text = "No GPS fix yet — start the tracker on the Configs page."
                setTextColor(0xFFFFFFFFL.toInt())
                setBackgroundColor(0xAA000000.toInt())
                val p = (8 * ctx.resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL,
                ).apply { setMargins(p, p, p, p * 2) }
            })
        }
        return root
    }

    // MapLibre's MapView needs the lifecycle callbacks pumped manually
    // (it isn't a LifecycleObserver). Without these the GL surface
    // leaks across rotations + recents-swipes.
    private fun savedInstanceStateOrNull(): Bundle? = arguments
    override fun onStart()   { super.onStart();   mapView?.onStart() }
    override fun onResume()  { super.onResume();  mapView?.onResume() }
    override fun onPause()   { mapView?.onPause();  super.onPause() }
    override fun onStop()    { mapView?.onStop();   super.onStop() }
    override fun onLowMemory(){ super.onLowMemory(); mapView?.onLowMemory() }
    override fun onDestroyView() {
        mapView?.onDestroy()
        mapView = null
        super.onDestroyView()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    companion object { fun newInstance() = MapsMapFragment() }
}
