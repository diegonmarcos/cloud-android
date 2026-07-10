package com.diegonmarcos.cloudnav.maps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

/**
 * Configs sub-page, rendered in one of two SECTIONS (data-driven host =
 * ConfigsFragment's TabLayout):
 *
 *   • [SECTION_TRACKER] — tracker controls (status, Start/Stop, reset),
 *     calibration sliders, and the export panel.
 *   • [SECTION_APIS]    — provider pickers for Search, Reverse geocoder and
 *     POI/Places + inline API-key editors.
 *
 * Provider lists are data-driven from build.json::ui.maps_providers via
 * [MapsProviders.loadFromBuildConfig]; selections persist in
 * [MapsProviderPrefs]. Light theme: dark text on light (palette shared with
 * [MapsStopsFragment]).
 */
class MapsConfigFragment : Fragment() {

    private val section: String get() = arguments?.getString(ARG_SECTION) ?: SECTION_TRACKER

    private var trackerStatusView: TextView? = null
    private var trackerCountsView: TextView? = null
    private var trackerPermsView:  TextView? = null
    private var trackerStartBtn:   android.widget.Button? = null
    private var trackerStopBtn:    android.widget.Button? = null
    private var trackerResetBtn:   android.widget.Button? = null
    private var trackerResetArmed = false

    /** Runtime-permission flow for Start. After the system dialog, if perms
     *  are STILL missing (permanently denied, or background-location which the
     *  dialog can't grant), fall back to opening app-details settings so the
     *  user can grant them — Start never silently no-ops. */
    private val trackerPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val ctx = context ?: return@registerForActivityResult
            if (MapsPermissions.missing(ctx).isEmpty()) {
                LocationTrackerService.startTracking(ctx)
            } else {
                Toast.makeText(ctx, "Grant Location (all the time) + Notifications to start tracking", Toast.LENGTH_LONG).show()
                openAppSettings(ctx)
            }
            refreshTrackerSection()
        }

    /** SAF file-picker for "Import JSON" — parses the picked file off-thread
     *  and inserts every trip as a Stop (see [MapsImport]). */
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val ctx = context ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            Toast.makeText(ctx, "Importing…", Toast.LENGTH_SHORT).show()
            Thread {
                val r = MapsImport.importFrom(ctx, uri)
                activity?.runOnUiThread {
                    Toast.makeText(
                        ctx,
                        r.error ?: "Imported ${r.inserted} places — open Timeline",
                        Toast.LENGTH_LONG,
                    ).show()
                    refreshTrackerSection()
                }
            }.start()
        }

    private fun openAppSettings(ctx: android.content.Context) {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (section == SECTION_TRACKER) refreshTrackerSection()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(MapsStopsFragment.COL_SURFACE)
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, p, p, p)
        }
        scroll.addView(root)

        if (section == SECTION_TRACKER) renderTracker(ctx, root, s)
        else renderApis(ctx, root)

        return scroll
    }

    // ── TRACKER section ──────────────────────────────────────────────
    private fun renderTracker(ctx: android.content.Context, root: LinearLayout, s: Bundle?) {
        val trackingPrefs = MapsTrackingPrefs(ctx)

        renderTrackerSection(ctx, root)

        root.addView(spacer(ctx, dp(ctx, 24)))
        root.addView(header(ctx, "Tracker calibration"))
        root.addView(caption(ctx, "Tune the foreground-service battery sip. Tighter intervals + smaller radius = more accurate, more battery."))

        root.addView(slider(ctx, "GPS interval while MOVING",
            currentValue = trackingPrefs.intervalMovingMs / 1000,
            min = 5, max = 120, unit = "s",
            onChange = { trackingPrefs.intervalMovingMs = it * 1000 }))
        root.addView(slider(ctx, "GPS interval while STOPPED",
            currentValue = trackingPrefs.intervalStoppedMs / 60_000,
            min = 1, max = 30, unit = "min",
            onChange = { trackingPrefs.intervalStoppedMs = it * 60_000 }))
        root.addView(slider(ctx, "Moving threshold",
            currentValue = trackingPrefs.movingThresholdMps.toInt(),
            min = 1, max = 10, unit = "m/s",
            onChange = { trackingPrefs.movingThresholdMps = it.toFloat() }))
        root.addView(slider(ctx, "Stop radius",
            currentValue = trackingPrefs.stopsRadiusM,
            min = 10, max = 200, unit = "m",
            onChange = { trackingPrefs.stopsRadiusM = it }))
        root.addView(slider(ctx, "Stop dwell time",
            currentValue = trackingPrefs.stopsDwellMin,
            min = 1, max = 30, unit = "min",
            onChange = { trackingPrefs.stopsDwellMin = it }))

        // Export panel (child fragment).
        root.addView(spacer(ctx, dp(ctx, 24)))
        root.addView(header(ctx, "Export"))
        val exportHost = FrameLayout(ctx).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        root.addView(exportHost)
        if (s == null && childFragmentManager.findFragmentById(exportHost.id) == null) {
            childFragmentManager.beginTransaction()
                .replace(exportHost.id, MapsExportFragment.newInstance())
                .commitAllowingStateLoss()
        }
    }

    private fun renderTrackerSection(ctx: android.content.Context, root: LinearLayout) {
        root.addView(header(ctx, "Tracker"))
        trackerStatusView = TextView(ctx).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setTextColor(MapsStopsFragment.COL_PRIMARY)
        }
        root.addView(trackerStatusView)
        root.addView(spacer(ctx, dp(ctx, 8)))

        trackerCountsView = TextView(ctx).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            setTextColor(MapsStopsFragment.COL_SECONDARY)
        }
        root.addView(trackerCountsView)
        root.addView(spacer(ctx, dp(ctx, 12)))

        trackerPermsView = TextView(ctx).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setTextColor(COL_WARN)
        }
        root.addView(trackerPermsView)

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val mt = dp(ctx, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = mt }
        }
        trackerStartBtn = android.widget.Button(ctx).apply {
            text = "Start"
            setOnClickListener {
                MapsHaptics.tap(it)
                val missing = MapsPermissions.missing(ctx)
                if (missing.isNotEmpty()) {
                    // Auto-open the permission grant (system dialog); the launcher
                    // callback falls back to app settings if anything's left.
                    trackerPermLauncher.launch(missing.toTypedArray())
                } else {
                    LocationTrackerService.startTracking(ctx)
                    refreshTrackerSection()
                }
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        trackerStopBtn = android.widget.Button(ctx).apply {
            text = "Stop"
            setOnClickListener {
                MapsHaptics.tap(it)
                LocationTrackerService.stopTracking(ctx)
                refreshTrackerSection()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(ctx, 8)
            }
        }
        btnRow.addView(trackerStartBtn)
        btnRow.addView(trackerStopBtn)
        root.addView(btnRow)

        root.addView(spacer(ctx, dp(ctx, 12)))
        root.addView(caption(ctx, "Data reset wipes every collected point + stop + cached place. Irreversible — Export first if you want a backup."))
        trackerResetBtn = android.widget.Button(ctx).apply {
            text = "Reset all tracker data"
            setOnClickListener {
                MapsHaptics.tap(it)
                if (!trackerResetArmed) {
                    trackerResetArmed = true
                    text = "Tap again to confirm wipe"
                } else {
                    MapsDb.get(ctx).clearAll()
                    // Also clear the demo-seed flag — otherwise a wiped DB stays
                    // permanently empty, since Load-demo-data would see "already
                    // seeded" and silently no-op forever after this reset.
                    MapsDemo.resetSeedFlag(ctx)
                    trackerResetArmed = false
                    text = "Reset all tracker data"
                    refreshTrackerSection()
                }
            }
        }
        root.addView(trackerResetBtn)

        // Tester seed — FULL daily coverage 1987-1992 (~2192 days) so Timeline/
        // Daily/Stops/Explored/MyTrips render real content without a live
        // tracker. Idempotent. Generates thousands of rows — off-thread.
        root.addView(spacer(ctx, dp(ctx, 12)))
        root.addView(caption(ctx, "No tracker history yet? Load a full 5-year demo trip (1987-1992, one day at a time — deliberately historical so it never conflicts with your real tracked data) to explore Timeline, Daily, Stops, Explored and MyTrips — every single day populated, cycling through 8 cities across 4 continents."))
        root.addView(android.widget.Button(ctx).apply {
            text = "Load demo data (1987-1992, full)"
            setOnClickListener {
                MapsHaptics.tap(it)
                text = "Loading…"; isEnabled = false
                val btn = this
                Thread {
                    val n = MapsDemo.seed(ctx)
                    activity?.runOnUiThread {
                        Toast.makeText(
                            ctx,
                            if (n > 0) "Loaded $n demo stops across ${MapsDemo.totalDays} days — open Timeline" else "Demo trip already loaded",
                            Toast.LENGTH_LONG,
                        ).show()
                        btn.text = "Load demo data (1987-1992, full)"; btn.isEnabled = true
                        refreshTrackerSection()
                    }
                }.start()
            }
        })

        // Import the user's OWN location history from a JSON file (e.g. the
        // travel-data.json export) → inserted as Stops via MapsImport.
        root.addView(spacer(ctx, dp(ctx, 12)))
        root.addView(caption(ctx, "Already have your own location history? Import a JSON file (travel-data.json / trips export) — each trip becomes a place in Timeline, Daily, Stops and Explored."))
        root.addView(android.widget.Button(ctx).apply {
            text = "Import location data (JSON)"
            setOnClickListener {
                MapsHaptics.tap(it)
                // application/json first; some pickers only expose text/* or */*.
                importLauncher.launch(arrayOf("application/json", "text/json", "text/plain", "*/*"))
            }
        })

        refreshTrackerSection()
    }

    private fun refreshTrackerSection() {
        val ctx = context ?: return
        val status = trackerStatusView ?: return
        val counts = trackerCountsView ?: return
        val perms  = trackerPermsView  ?: return

        val trackerPrefs = MapsTrackerPrefs(ctx)
        val db = MapsDb.get(ctx)
        val missing = MapsPermissions.missing(ctx)
        val running = trackerPrefs.enabled

        status.text = when {
            missing.isNotEmpty() -> "● Permissions missing"
            running              -> "● Tracker running"
            else                 -> "● Tracker idle"
        }
        status.setTextColor(when {
            missing.isNotEmpty() -> 0xFFEF4444.toInt()
            running              -> 0xFF16A34A.toInt()
            else                 -> COL_WARN
        })

        val lastTs = trackerPrefs.lastFixTs
        val lastFixStr = if (lastTs <= 0L) "no fix yet" else
            "last fix " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(lastTs))
        counts.text =
            "Points: ${db.pointCount()}\n" +
            "Stops:  ${db.stopCount()}\n" +
            lastFixStr

        perms.text = if (missing.isEmpty()) ""
            else "Needs: " + missing.joinToString(", ") {
                it.substringAfterLast('.').replace('_', ' ').lowercase()
            } + "  ← tap Start to request"
        perms.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE

        trackerStartBtn?.isEnabled = !running
        trackerStopBtn ?.isEnabled =  running
    }

    // ── APIs section ─────────────────────────────────────────────────
    private fun renderApis(ctx: android.content.Context, root: LinearLayout) {
        val providerPrefs = MapsProviderPrefs(ctx)
        val apiKeyPrefs   = MapsApiKeyPrefs(ctx)
        val all           = MapsProviders.loadFromBuildConfig()

        // Basemap: "Auto" lets each screen keep its own smart default (Navigation
        // dark, Places satellite, Routes light, …) resolved to vector/hybrid or
        // raster by the family choice; picking a concrete style below PINS it
        // everywhere, overriding every screen's default. Vector/hybrid styles
        // fall back to raster automatically if their fetch fails at runtime.
        val basemapPrefs = MapsBasemapPrefs(ctx)
        root.addView(header(ctx, "Basemap"))
        root.addView(caption(ctx, "Auto keeps each screen's own look (Navigation dark, Places satellite, Routes light…), just swapping vector/raster/hybrid rendering. Pinning a specific map below overrides that everywhere."))
        root.addView(basemapRow(ctx, "Auto — Vector (recommended)", "Each screen's default, rendered as English-labelled vector/hybrid",
            isSelected = basemapPrefs.explicitStyleKey == null && basemapPrefs.preferVectorFamily,
            onPick = { basemapPrefs.explicitStyleKey = null; basemapPrefs.preferVectorFamily = true; rerender() }))
        root.addView(spacer(ctx, dp(ctx, 6)))
        root.addView(basemapRow(ctx, "Auto — Raster", "Each screen's default, rendered as fixed local-language tile images",
            isSelected = basemapPrefs.explicitStyleKey == null && !basemapPrefs.preferVectorFamily,
            onPick = { basemapPrefs.explicitStyleKey = null; basemapPrefs.preferVectorFamily = false; rerender() }))
        root.addView(spacer(ctx, dp(ctx, 14)))
        MapStyles.order.forEach { key ->
            val s = MapStyles.get(key)
            val kind = when { s.hybrid -> "Hybrid — imagery + English vector overlay"; s.vectorStyleUrl != null -> "Vector — English labels, on-device"; else -> "Raster — fixed tile images" }
            root.addView(basemapRow(ctx, s.label, kind,
                isSelected = basemapPrefs.explicitStyleKey == key,
                onPick = { basemapPrefs.explicitStyleKey = key; rerender() }))
            root.addView(spacer(ctx, dp(ctx, 6)))
        }
        root.addView(spacer(ctx, dp(ctx, 20)))

        // Search (forward geocode) — the universal search bar's backend.
        root.addView(header(ctx, "Search (geocoder)"))
        root.addView(caption(ctx, "Backs the search bar across Routes / Navigation / Places — turns 'Berlin', a street, a restaurant, anything into coordinates."))
        for (provider in MapsProviders.forKind(all, "search")) {
            root.addView(providerRow(ctx, provider, apiKeyPrefs,
                isSelected = providerPrefs.activeSearch == provider.id,
                onPick     = { providerPrefs.activeSearch = provider.id; rerender() }))
            root.addView(spacer(ctx, dp(ctx, 6)))
        }

        // Reverse geocoder.
        root.addView(spacer(ctx, dp(ctx, 20)))
        root.addView(header(ctx, "Reverse geocoder"))
        root.addView(caption(ctx, "Resolves a GPS point into city + neighborhood. Fires once per Stop event."))
        for (provider in MapsProviders.forKind(all, "reverse")) {
            root.addView(providerRow(ctx, provider, apiKeyPrefs,
                isSelected = providerPrefs.activeReverse == provider.id,
                onPick     = { providerPrefs.activeReverse = provider.id; rerender() }))
            root.addView(spacer(ctx, dp(ctx, 6)))
        }

        // POI / Places.
        root.addView(spacer(ctx, dp(ctx, 20)))
        root.addView(header(ctx, "POI / Places"))
        root.addView(caption(ctx, "Resolves an amenity (restaurant, shop, café) and backs the Places category islands (Overpass)."))
        for (provider in MapsProviders.forKind(all, "poi")) {
            root.addView(providerRow(ctx, provider, apiKeyPrefs,
                isSelected = providerPrefs.activePoi == provider.id,
                onPick     = { providerPrefs.activePoi = provider.id; rerender() }))
            root.addView(spacer(ctx, dp(ctx, 6)))
        }
    }

    /** Re-attach to repaint the radio state after a pick. */
    private fun rerender() {
        parentFragmentManager.beginTransaction().detach(this).attach(this).commit()
    }

    private fun basemapRow(
        ctx: android.content.Context,
        label: String,
        sub: String,
        isSelected: Boolean,
        onPick: () -> Unit,
    ): View {
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 12); setPadding(pad, pad, pad, pad)
            setBackgroundColor(if (isSelected) COL_TILE_SELECTED else MapsStopsFragment.COL_TILE)
            isClickable = true; isFocusable = true
            setOnClickListener { MapsHaptics.tap(it); onPick() }
        }
        tile.addView(TextView(ctx).apply {
            text = (if (isSelected) "● " else "○ ") + label
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setTextColor(MapsStopsFragment.COL_PRIMARY)
        })
        tile.addView(TextView(ctx).apply {
            text = sub
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setTextColor(MapsStopsFragment.COL_SECONDARY)
        })
        return tile
    }

    private fun providerRow(
        ctx: android.content.Context,
        p: MapsProviders.Provider,
        apiKeyPrefs: MapsApiKeyPrefs,
        isSelected: Boolean,
        onPick: () -> Unit,
    ): View {
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 12); setPadding(pad, pad, pad, pad)
            setBackgroundColor(if (isSelected) COL_TILE_SELECTED else MapsStopsFragment.COL_TILE)
            isClickable = true; isFocusable = true
            setOnClickListener { MapsHaptics.tap(it); onPick() }
        }
        tile.addView(TextView(ctx).apply {
            text = (if (isSelected) "● " else "○ ") + p.label +
                "   [" + hostShort(p.host) + "]" +
                "   " + p.freeTier
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setTextColor(MapsStopsFragment.COL_PRIMARY)
        })
        if (p.requiresApiKey) {
            val has = apiKeyPrefs.hasKey(p.id)
            tile.addView(TextView(ctx).apply {
                text = if (has) "API key ✓" else "Needs API key ↓"
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setTextColor(if (has) MapsStopsFragment.COL_ACCENT else COL_WARN)
                setPadding(0, dp(ctx, 4), 0, 0)
            })
            tile.addView(EditText(ctx).apply {
                hint = "API key for ${p.label}"
                setText(apiKeyPrefs.get(p.id))
                setTextColor(MapsStopsFragment.COL_PRIMARY)
                setHintTextColor(MapsStopsFragment.COL_SECONDARY)
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) { apiKeyPrefs.set(p.id, s?.toString().orEmpty()) }
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                })
            })
        }
        return tile
    }

    private fun hostShort(host: String): String = when (host) {
        "free-noauth"  -> "FREE · no account"
        "free-account" -> "FREE · account"
        "paid"         -> "PAID"
        "self"         -> "SELF-HOSTED"
        else           -> host.uppercase()
    }

    private fun header(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        setTextColor(MapsStopsFragment.COL_PRIMARY)
        setPadding(0, 0, 0, dp(ctx, 8))
    }
    private fun caption(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_Material_Body2)
        setTextColor(MapsStopsFragment.COL_SECONDARY)
        setPadding(0, 0, 0, dp(ctx, 12))
    }
    private fun spacer(ctx: android.content.Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }
    private fun dp(ctx: android.content.Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun slider(
        ctx: android.content.Context,
        title: String,
        currentValue: Int,
        min: Int,
        max: Int,
        unit: String,
        onChange: (Int) -> Unit,
    ): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 8))
        }
        val label = TextView(ctx).apply {
            text = "$title = $currentValue$unit"
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setTextColor(MapsStopsFragment.COL_PRIMARY)
        }
        col.addView(label)
        col.addView(SeekBar(ctx).apply {
            this.max = max - min
            progress = (currentValue - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    label.text = "$title = ${p + min}$unit"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    MapsHaptics.tap(this@apply); onChange(progress + min)
                }
            })
        })
        return col
    }

    companion object {
        const val SECTION_TRACKER = "tracker"
        const val SECTION_APIS    = "apis"
        private const val ARG_SECTION = "section"

        private const val COL_WARN          = 0xFFFBBF24.toInt()  // bright amber (readable on dark)
        private const val COL_TILE_SELECTED = 0xFF16351F.toInt()  // dark green tint (selected on dark)

        fun newInstance(section: String = SECTION_TRACKER) = MapsConfigFragment().apply {
            arguments = Bundle().apply { putString(ARG_SECTION, section) }
        }
    }
}
