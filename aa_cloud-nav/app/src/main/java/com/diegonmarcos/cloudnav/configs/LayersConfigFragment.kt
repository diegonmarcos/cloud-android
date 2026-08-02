package com.diegonmarcos.cloudnav.configs

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.maps.MapsLayerPrefs
import com.diegonmarcos.cloudnav.maps.MapsStopsFragment

/**
 * Layers tab: per-layer visual settings, one section per layer. Currently
 * just Terrain's exaggeration, since that's the only layer with a tunable
 * knob today — [MapsLayerPrefs] is the native, persisted source of truth,
 * and [com.diegonmarcos.cloudnav.TerrainActivity] pushes/reads this same
 * value into its WebView page (see terrain_map.html's `AndroidBridge`
 * wiring) so this tab and the in-view slider never drift out of sync.
 */
class LayersConfigFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val prefs = MapsLayerPrefs(ctx)
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(MapsStopsFragment.COL_SURFACE)
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, p, p, p)
        }
        scroll.addView(root)

        root.addView(header(ctx, "Layers"))
        root.addView(caption(ctx, "Per-layer visual settings. Changes apply immediately, including live inside an already-open view."))

        root.addView(spacer(ctx, dp(ctx, 16)))
        root.addView(header(ctx, "Terrain"))
        root.addView(caption(ctx, "3D mountain relief exaggeration in the Terrain view (Map style menu > Terrain)."))
        root.addView(slider(ctx, "Exaggeration",
            currentValue = prefs.terrainExaggeration,
            min = 1, max = 20, unit = "x",
            onChange = { prefs.terrainExaggeration = it }))

        return scroll
    }

    // ── layout helpers, same visual style as MapsConfigFragment ───────
    private fun slider(
        ctx: Context,
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
                    onChange(progress + min)
                }
            })
        })
        return col
    }

    private fun header(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        setTextColor(MapsStopsFragment.COL_PRIMARY)
        setPadding(0, 0, 0, dp(ctx, 8))
    }

    private fun caption(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_Material_Body2)
        setTextColor(MapsStopsFragment.COL_SECONDARY)
        setPadding(0, 0, 0, dp(ctx, 12))
    }

    private fun spacer(ctx: Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
