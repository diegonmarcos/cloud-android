package com.diegonmarcos.superapp.media

import android.content.Context
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import org.json.JSONObject

/**
 * Runtime config for the media panel, pushed in once by the app's
 * `App.onCreate` (the only place that can read BuildConfig — libs can't).
 * Keeps libs:media free of any compile-time dep on app/ and keeps every
 * knob data-driven from build.json::keyboard_media (FIRE RULE #4/#6).
 *
 * Config JSON shape (base64-decoded from BuildConfig.MEDIA_CONFIG_B64):
 * {
 *   "enabled": true,
 *   "sticker": { "enabled": true },
 *   "gif": {
 *     "provider": "tenor",            // "tenor" | "giphy"
 *     "limit": 30,
 *     "tenor": { "base_url": "...", "categories": [ {"id","label","query"}... ] },
 *     "giphy": { "base_url": "...", "categories": [ ... ] }
 *   }
 * }
 */
object MediaRuntime {

    data class GifConf(
        val provider: String,
        val baseUrl: String,
        val apiKey: String,
        val limit: Int,
        val categories: List<MediaCategory>
    )

    var enabled = false; private set
    var stickersEnabled = false; private set
    var gif: GifConf? = null; private set

    /** GIF search is usable only when a provider key was supplied. */
    val gifEnabled: Boolean get() = gif?.apiKey?.isNotBlank() == true

    /** Shared Coil loader with animated-GIF/WebP decoders. Lazily built. */
    @Volatile private var loader: ImageLoader? = null
    fun imageLoader(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                else add(GifDecoder.Factory())
            }
            .crossfade(true)
            .build()
            .also { loader = it }
    }

    fun configure(configB64: String?, tenorKey: String?, giphyKey: String?) {
        val json = decodeB64(configB64) ?: return
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return
        enabled = root.optBoolean("enabled", false)
        stickersEnabled = root.optJSONObject("sticker")?.optBoolean("enabled", true) ?: true

        val g = root.optJSONObject("gif")
        if (g != null) {
            val provider = g.optString("provider", "tenor").lowercase()
            val sub = g.optJSONObject(provider)
            val key = if (provider == "giphy") giphyKey else tenorKey
            if (sub != null) {
                gif = GifConf(
                    provider = provider,
                    baseUrl = sub.optString("base_url"),
                    apiKey = key?.trim().orEmpty(),
                    limit = g.optInt("limit", 30),
                    categories = parseCategories(sub.optJSONArray("categories"))
                )
            }
        }
    }

    private fun parseCategories(arr: org.json.JSONArray?): List<MediaCategory> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                val id = it.optString("id"); if (id.isBlank()) return@let null
                MediaCategory(id, it.optString("label", id), it.optString("query", id))
            }
        }
    }

    private fun decodeB64(s: String?): String? {
        if (s.isNullOrBlank()) return null
        return runCatching { String(android.util.Base64.decode(s, android.util.Base64.DEFAULT)) }.getOrNull()
    }
}
