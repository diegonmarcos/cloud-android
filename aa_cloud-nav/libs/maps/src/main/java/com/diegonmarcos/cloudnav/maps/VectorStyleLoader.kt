package com.diegonmarcos.cloudnav.maps

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches a public vector-tile GL style (e.g. OpenFreeMap's "liberty") and
 * rewrites its label layers to prefer an English name field, so city/country/
 * street labels render in English instead of each place's local script.
 *
 * OpenMapTiles-schema styles (confirmed against tiles.openfreemap.org/styles/
 * liberty) express every name label as a `case`/`coalesce` expression built on
 * `name`, `name:latin`, `name:nonlatin`, `name_en` — never a bare string — so
 * we detect "is this a name label" by checking the serialized expression for
 * the substring "name" and leave everything else (road ref shields use `ref`,
 * not `name`) untouched. Network + JSON parsing, so call off the main thread.
 */
object VectorStyleLoader {

    /** Returns the localized style JSON, or null on any network/parse failure
     *  (caller falls back / toasts — never crashes the map). */
    fun loadLocalized(styleUrl: String, preferField: String): String? = runCatching {
        val body = fetch(styleUrl) ?: return null
        localize(body, preferField)
    }.getOrNull()

    /** Pure JSON rewrite (no network) — every name-label symbol layer gets a
     *  `["coalesce", ["get", preferField], ["get","name:latin"], ["get","name"]]`
     *  text-field. Non-name labels (road-ref shields) are left untouched.
     *  Exposed for testing without a network round-trip. */
    fun localize(styleJson: String, preferField: String): String {
        val root = JSONObject(styleJson)
        val layers = root.optJSONArray("layers") ?: return root.toString()
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (layer.optString("type") != "symbol") continue
            val layout = layer.optJSONObject("layout") ?: continue
            val field = layout.opt("text-field") ?: continue
            if (!field.toString().contains("name")) continue  // e.g. road-ref shields — leave alone
            layout.put(
                "text-field",
                JSONArray().put("coalesce")
                    .put(JSONArray().put("get").put(preferField))
                    .put(JSONArray().put("get").put("name:latin"))
                    .put(JSONArray().put("get").put("name")),
            )
        }
        return root.toString()
    }

    private fun fetch(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "CloudNav/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }.getOrNull()
}
