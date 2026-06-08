package com.diegonmarcos.superapp.maps

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Maps → Export page. Two buttons that hand the user a Storage-Access-
 * Framework document picker; the fragment then writes a serialised
 * tracker dump to the chosen Uri.
 *
 * Formats:
 *   • GeoJSON FeatureCollection — one Feature per Stop with
 *     {type:"Point", coordinates:[lon,lat]} + properties (started_at,
 *     ended_at, place_name, neighborhood, city, country). Stops only;
 *     raw points would balloon the file size and aren't usually
 *     interesting to re-import. (Push 3.5 can add a "Points + Stops"
 *     toggle if needed.)
 *   • CSV — flat row-per-Stop format for spreadsheet folks. Same
 *     columns.
 *
 * No external storage permission needed — SAF returns a write-grant
 * Uri scoped to the picked location.
 */
class MapsExportFragment : Fragment() {

    private var pendingFormat: String = "geojson"

    private val picker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(
            // Mime type the system uses to filter the picker UI.
            // Override per pendingFormat at call time via setType.
            "application/json",
        )
    ) { uri: Uri? -> if (uri != null) writeTo(uri, pendingFormat) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16); setPadding(p, p, p, p)
        }
        scroll.addView(root)

        root.addView(TextView(ctx).apply {
            text = "Export"
            setTextColor(0xFFFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setPadding(0, 0, 0, dp(ctx, 8))
        })
        root.addView(TextView(ctx).apply {
            text = "Save your tracker DB as a portable file. GeoJSON for maps + GIS tools, CSV for spreadsheets. Stops only — raw GPS points stay in the local DB."
            setTextColor(0xAAFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            setPadding(0, 0, 0, dp(ctx, 16))
        })

        root.addView(Button(ctx).apply {
            text = "Export GeoJSON"
            setOnClickListener {
                MapsHaptics.tap(it)
                pendingFormat = "geojson"
                picker.launch("cloud-superapp-stops-${nowStamp()}.geojson")
            }
        })
        root.addView(Button(ctx).apply {
            text = "Export CSV"
            setOnClickListener {
                MapsHaptics.tap(it)
                pendingFormat = "csv"
                picker.launch("cloud-superapp-stops-${nowStamp()}.csv")
            }
            val mt = dp(ctx, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = mt }
        })
        return scroll
    }

    /** Stream the chosen dump into the SAF-granted Uri. Runs on the
     *  main thread — DB is small enough (a few thousand stops max for
     *  a 5y window) that we don't need a background thread. */
    private fun writeTo(uri: Uri, format: String) {
        val ctx = context ?: return
        val now = System.currentTimeMillis()
        val from = now - 5L * 365L * 24L * 3600L * 1000L
        val stops = MapsDb.get(ctx).stopsBetween(from, now)
        val payload = when (format) {
            "csv"     -> stopsToCsv(stops)
            else      -> stopsToGeoJson(stops)
        }
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
        }
        android.widget.Toast.makeText(ctx,
            "Exported ${stops.size} stops as $format",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    /** GeoJSON FeatureCollection. */
    private fun stopsToGeoJson(stops: List<MapsDb.RichStop>): String {
        val root = org.json.JSONObject()
        root.put("type", "FeatureCollection")
        val features = org.json.JSONArray()
        for (s in stops) {
            val feat = org.json.JSONObject()
            feat.put("type", "Feature")
            val geom = org.json.JSONObject()
            geom.put("type", "Point")
            geom.put("coordinates", org.json.JSONArray().apply {
                put(s.lon); put(s.lat)
            })
            feat.put("geometry", geom)
            val props = org.json.JSONObject().apply {
                put("started_at", s.startedAt)
                put("ended_at",   s.endedAt ?: org.json.JSONObject.NULL)
                put("place_name", s.placeName ?: org.json.JSONObject.NULL)
                put("neighborhood", s.neighborhood ?: org.json.JSONObject.NULL)
                put("city",       s.city ?: org.json.JSONObject.NULL)
                put("country",    s.country ?: org.json.JSONObject.NULL)
            }
            feat.put("properties", props)
            features.put(feat)
        }
        root.put("features", features)
        return root.toString(2)
    }

    /** CSV — RFC-4180-ish escaping for embedded commas / quotes. */
    private fun stopsToCsv(stops: List<MapsDb.RichStop>): String {
        val sb = StringBuilder()
        sb.appendLine("started_at,ended_at,lat,lon,place_name,neighborhood,city,country")
        for (s in stops) {
            sb.append(s.startedAt).append(",")
            sb.append(s.endedAt ?: "").append(",")
            sb.append(s.lat).append(",")
            sb.append(s.lon).append(",")
            sb.append(csv(s.placeName)).append(",")
            sb.append(csv(s.neighborhood)).append(",")
            sb.append(csv(s.city)).append(",")
            sb.append(csv(s.country)).appendLine()
        }
        return sb.toString()
    }
    private fun csv(v: String?): String {
        if (v.isNullOrEmpty()) return ""
        val esc = v.replace("\"", "\"\"")
        return if (esc.any { it == ',' || it == '\n' || it == '"' }) "\"$esc\"" else esc
    }

    private fun nowStamp(): String = java.text.SimpleDateFormat(
        "yyyy-MM-dd-HHmm", java.util.Locale.US
    ).format(java.util.Date())

    private fun dp(ctx: android.content.Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = MapsExportFragment() }
}
