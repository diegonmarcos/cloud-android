package com.diegonmarcos.cloudnav.places

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.diegonmarcos.cloudnav.NavConfig
import com.diegonmarcos.cloudnav.PoiField
import com.diegonmarcos.cloudnav.maps.MapsProviderClient
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rich POI detail card (tap a Places pin or result row). Shows everything the
 * provider returned: name, type, distance-from-here, then the data-driven
 * [NavConfig.poiDetailFields] rows (phone/website/hours/address/…) with tap
 * actions, an "Open in maps / Directions" action, and a collapsible dump of any
 * remaining raw OSM tags. No layout XML — built in code like the rest of the app.
 */
object PlaceDetailSheet {

    private val COL_BG = 0xFF161A22.toInt()
    private val COL_TEXT = 0xFFFFFFFF.toInt()
    private val COL_SUB = 0xFFA9B0BD.toInt()
    private val COL_ACCENT = 0xFF4DA3FF.toInt()

    /** [originLat]/[originLon] = the user's current location for the distance
     *  readout (pass NaN when unknown → distance row is omitted). */
    fun show(ctx: Context, hit: MapsProviderClient.SearchHit, originLat: Double, originLon: Double) {
        val dialog = BottomSheetDialog(ctx)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }

        // Title.
        col.addView(TextView(ctx).apply {
            text = hit.title; textSize = 22f; setTextColor(COL_TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })

        // Type · distance.
        val meta = buildList {
            hit.type?.let { add(prettify(it)) }
            if (!originLat.isNaN() && !originLon.isNaN()) {
                val km = haversineKm(originLat, originLon, hit.lat, hit.lon)
                add(if (km < 1.0) "${(km * 1000).roundToInt()} m away" else "%.1f km away".format(km))
            }
        }.joinToString("  ·  ")
        if (meta.isNotEmpty()) col.addView(TextView(ctx).apply {
            text = meta; textSize = 13f; setTextColor(COL_ACCENT); setPadding(0, dp(4), 0, dp(10))
        })

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // Data-driven info rows (only those present in the tag set).
        val shown = HashSet<String>()
        NavConfig.poiDetailFields.forEach { field ->
            val (key, value) = resolve(hit.tags, field) ?: return@forEach
            shown += key
            body.addView(infoRow(ctx, ::dp, field, value))
        }

        // Remaining raw tags (everything the API returned, nothing hidden).
        val extra = hit.tags.filterKeys { it !in shown && it != "name" }
        if (extra.isNotEmpty()) {
            body.addView(sectionLabel(ctx, ::dp, "More (raw OSM tags)"))
            extra.forEach { (k, v) ->
                body.addView(TextView(ctx).apply {
                    text = "$k = $v"; textSize = 12f; setTextColor(COL_SUB)
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(0, dp(2), 0, dp(2))
                })
            }
        }

        col.addView(ScrollView(ctx).apply { addView(body) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })

        // Directions / open-in-maps (geo: intent → any installed maps app).
        col.addView(actionButton(ctx, ::dp, "🧭  Directions / open in maps") {
            openGeo(ctx, hit.lat, hit.lon, hit.title)
        })

        dialog.setContentView(col)
        dialog.behavior.peekHeight = dp(420)
        dialog.show()
    }

    // ── rows ─────────────────────────────────────────────────────────────
    private fun infoRow(ctx: Context, dp: (Int) -> Int, field: PoiField, value: String): View {
        val row = TextView(ctx).apply {
            text = "${field.emoji}  ${field.label}: $value"
            textSize = 15f; setPadding(0, dp(9), 0, dp(9))
        }
        when (field.kind) {
            "url" -> { row.setTextColor(COL_ACCENT); row.setOnClickListener { openUrl(ctx, value) } }
            "phone" -> { row.setTextColor(COL_ACCENT); row.setOnClickListener { openIntent(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$value"))) } }
            "email" -> { row.setTextColor(COL_ACCENT); row.setOnClickListener { openIntent(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$value"))) } }
            else -> row.setTextColor(COL_TEXT)
        }
        return row
    }

    private fun sectionLabel(ctx: Context, dp: (Int) -> Int, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 12f; setTextColor(COL_SUB)
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun actionButton(ctx: Context, dp: (Int) -> Int, label: String, onClick: () -> Unit) =
        TextView(ctx).apply {
            text = label; textSize = 15f; setTextColor(COL_TEXT); gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFF243040.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
            setOnClickListener { onClick() }
        }

    /** First present of tag|alt → (matchedKey, value). Null when none set. */
    private fun resolve(tags: Map<String, String>, field: PoiField): Pair<String, String>? {
        (listOf(field.tag) + field.alt).forEach { k ->
            val v = tags[k]
            if (!v.isNullOrBlank()) return k to v
        }
        return null
    }

    private fun prettify(s: String) = s.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun openUrl(ctx: Context, raw: String) {
        val url = if (raw.startsWith("http")) raw else "https://$raw"
        openIntent(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun openGeo(ctx: Context, lat: Double, lon: Double, label: String) {
        val enc = Uri.encode(label)
        openIntent(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon($enc)")))
    }

    private fun openIntent(ctx: Context, intent: Intent) {
        try { ctx.startActivity(intent) }
        catch (e: ActivityNotFoundException) { Toast.makeText(ctx, "No app to handle that", Toast.LENGTH_SHORT).show() }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
