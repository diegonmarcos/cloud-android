package com.diegonmarcos.superapp.maps

import android.os.Bundle
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
import androidx.fragment.app.Fragment

/**
 * Configs → Maps page. One programmatic ScrollView with three sections:
 *
 *  1. Reverse geocoder picker (provider list filtered by kind=reverse).
 *  2. POI provider picker (provider list filtered by kind=poi).
 *  3. API keys editor — one inline EditText per provider that has
 *     requires_api_key=true. Saved as-you-type via TextWatcher into
 *     [MapsApiKeyPrefs].
 *  4. Calibration sliders — interval-moving, interval-stopped,
 *     moving-threshold, stops-radius, stops-dwell — written to
 *     [MapsTrackingPrefs] on slider release.
 *
 * The provider lists are data-driven from build.json::ui.maps_providers
 * via [MapsProviders.loadFromBuildConfig]; the picker fields are
 * SharedPreferences-backed via [MapsProviderPrefs]. Adding a new
 * provider in build.json + rebuilding lights it up here automatically.
 */
class MapsConfigFragment : Fragment() {

    // Tracker section state — held so refresh() can re-paint without
    // rebuilding the whole fragment. Lazy-init in renderTrackerSection.
    private var trackerStatusView: android.widget.TextView? = null
    private var trackerCountsView: android.widget.TextView? = null
    private var trackerPermsView:  android.widget.TextView? = null
    private var trackerStartBtn:   android.widget.Button? = null
    private var trackerStopBtn:    android.widget.Button? = null
    private var trackerResetBtn:   android.widget.Button? = null
    private var trackerResetArmed = false
    private val TRACKER_PERM_REQUEST_CODE = 9991

    override fun onResume() {
        super.onResume()
        refreshTrackerSection()
    }

    /** Build the Tracker block at the top of the Configs page —
     *  status badge, counters, permission hint, Start/Stop buttons,
     *  Reset-all (two-tap-armed). Same shape as the old standalone
     *  Maps/Tracker page. */
    private fun renderTrackerSection(ctx: android.content.Context, root: LinearLayout) {
        root.addView(header(ctx, "Tracker"))
        trackerStatusView = android.widget.TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        }
        root.addView(trackerStatusView)
        root.addView(spacer(ctx, dp(ctx, 8)))

        trackerCountsView = android.widget.TextView(ctx).apply {
            setTextColor(0xAAFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
        }
        root.addView(trackerCountsView)
        root.addView(spacer(ctx, dp(ctx, 12)))

        trackerPermsView = android.widget.TextView(ctx).apply {
            setTextColor(0xFFF59E0B.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
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
                    MapsPermissions.request(requireActivity(), missing, TRACKER_PERM_REQUEST_CODE)
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
        root.addView(caption(ctx, "Data reset wipes every collected point + stop + cached place. Irreversible — Export from the Export page first if you want a backup."))
        trackerResetBtn = android.widget.Button(ctx).apply {
            text = "Reset all tracker data"
            setOnClickListener {
                MapsHaptics.tap(it)
                if (!trackerResetArmed) {
                    trackerResetArmed = true
                    text = "Tap again to confirm wipe"
                } else {
                    MapsDb.get(ctx).clearAll()
                    trackerResetArmed = false
                    text = "Reset all tracker data"
                    refreshTrackerSection()
                }
            }
        }
        root.addView(trackerResetBtn)

        refreshTrackerSection()
    }

    /** Re-read tracker state and update labels + button enablement.
     *  Cheap; safe to call on every onResume / button-press. Returns
     *  silently if the section hasn't been rendered yet (e.g. called
     *  before onCreateView). */
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
            running              -> 0xFF22C55E.toInt()
            else                 -> 0xFFF59E0B.toInt()
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val providerPrefs = MapsProviderPrefs(ctx)
        val apiKeyPrefs   = MapsApiKeyPrefs(ctx)
        val trackingPrefs = MapsTrackingPrefs(ctx)
        val all           = MapsProviders.loadFromBuildConfig()

        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, p, p, p)
        }
        scroll.addView(root)

        // ── Tracker section (folded in from the removed maps/tracker
        //    page so every Maps knob is one tap away). Status badge,
        //    counters, permission prompt, Start/Stop, Reset-all. ──
        renderTrackerSection(ctx, root)

        root.addView(spacer(ctx, dp(ctx, 24)))

        // ── Reverse geocoder section ───────────────────────────────
        root.addView(header(ctx, "Reverse geocoder"))
        root.addView(caption(ctx, "Resolves a GPS point into city + neighborhood. Fires once per Stop event."))
        for (provider in MapsProviders.forKind(all, "reverse")) {
            root.addView(providerRow(ctx, provider, apiKeyPrefs,
                isSelected = providerPrefs.activeReverse == provider.id,
                onPick     = {
                    providerPrefs.activeReverse = provider.id
                    parentFragmentManager.beginTransaction().detach(this).attach(this).commit()
                }))
            root.addView(spacer(ctx, dp(ctx, 6)))
        }

        // ── POI provider section ───────────────────────────────────
        root.addView(spacer(ctx, dp(ctx, 20)))
        root.addView(header(ctx, "POI / Places"))
        root.addView(caption(ctx, "Resolves a GPS point into a named amenity (restaurant, shop, café). Fired alongside the reverse geocoder on each Stop event."))
        for (provider in MapsProviders.forKind(all, "poi")) {
            root.addView(providerRow(ctx, provider, apiKeyPrefs,
                isSelected = providerPrefs.activePoi == provider.id,
                onPick     = {
                    providerPrefs.activePoi = provider.id
                    parentFragmentManager.beginTransaction().detach(this).attach(this).commit()
                }))
            root.addView(spacer(ctx, dp(ctx, 6)))
        }

        // ── Calibration section ────────────────────────────────────
        root.addView(spacer(ctx, dp(ctx, 24)))
        root.addView(header(ctx, "Tracker calibration"))
        root.addView(caption(ctx, "Tune the foreground-service battery sip. Tighter intervals + smaller radius = more accurate, more battery. The Stop detector controls how many reverse-geocode + POI calls fire per day."))

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

        root.addView(spacer(ctx, dp(ctx, 8)))
        root.addView(caption(ctx, "Tip: ~10 stops/day × 30 days ≈ 300 reverse-geocode calls/month. LocationIQ free 5k/day handles this 17× over. Public Nominatim is fair-use OK for this volume; if you exceed it consider self-hosting Photon or Nominatim."))

        // ── Export section ────────────────────────────────────────
        // Folded in from the removed Maps/Export page so every Maps
        // knob lives in one scroll. The actual SAF picker + writer
        // lives in MapsExportFragment which we embed here as a child
        // fragment via childFragmentManager. Keeps a single source of
        // truth for the export logic.
        root.addView(spacer(ctx, dp(ctx, 24)))
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

        return scroll
    }

    /** A selectable card for one provider — label, host badge, key
     *  status, free-tier note, and (if the provider needs a key) an
     *  inline EditText that saves the key as-you-type. */
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
            setBackgroundColor(if (isSelected) 0x447C3AED.toInt() else 0x22FFFFFFL.toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { MapsHaptics.tap(it); onPick() }
        }
        // Top row: ● label  · [host badge] · free-tier
        tile.addView(TextView(ctx).apply {
            text = (if (isSelected) "● " else "○ ") + p.label +
                "   [" + hostShort(p.host) + "]" +
                "   " + p.freeTier
            setTextColor(0xFFFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        })
        // Status hint: KEY SET / NEEDS KEY (only if requires_api_key)
        if (p.requiresApiKey) {
            val has = apiKeyPrefs.hasKey(p.id)
            tile.addView(TextView(ctx).apply {
                text = if (has) "API key ✓" else "Needs API key ↓"
                setTextColor(if (has) 0xFF22C55E.toInt() else 0xFFF59E0B.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, dp(ctx, 4), 0, 0)
            })
            tile.addView(EditText(ctx).apply {
                hint = "API key for ${p.label}"
                setText(apiKeyPrefs.get(p.id))
                setTextColor(0xFFFFFFFFL.toInt())
                setHintTextColor(0x66FFFFFFL.toInt())
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) { apiKeyPrefs.set(p.id, s?.toString().orEmpty()) }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                })
            })
        }
        return tile
    }

    /** Friendly badge text per host tier. */
    private fun hostShort(host: String): String = when (host) {
        "free-noauth"  -> "FREE · no account"
        "free-account" -> "FREE · account"
        "paid"         -> "PAID"
        "self"         -> "SELF-HOSTED"
        else           -> host.uppercase()
    }

    private fun header(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFFFFFFFF.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        setPadding(0, 0, 0, dp(ctx, 8))
    }
    private fun caption(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(0xAAFFFFFFL.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Body2)
        setPadding(0, 0, 0, dp(ctx, 12))
    }
    private fun spacer(ctx: android.content.Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }
    private fun dp(ctx: android.content.Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** A labelled SeekBar that prints "{title} = {value}{unit}" and
     *  emits the new integer value on user-release. Range is min..max
     *  inclusive; current value is clamped. */
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
            setTextColor(0xFFFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
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

    companion object { fun newInstance() = MapsConfigFragment() }
}
