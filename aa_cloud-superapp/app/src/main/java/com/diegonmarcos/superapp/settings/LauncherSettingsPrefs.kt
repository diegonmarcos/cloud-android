package com.diegonmarcos.superapp.settings

import com.diegonmarcos.superapp.BuildConfig
import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configs → Launcher → Screensaver + Others. Single source of truth for the
 * user's launcher-settings choices: the picked screensaver, the per-feature
 * on/off toggles (pets / cube / stars / haptics / eye-protection), and the two
 * sliders (system brightness, eye-protection strength).
 *
 * Defaults come from build.json (baked into BuildConfig.UI_LAUNCHER_SETTINGS_B64
 * + UI_SCREENSAVERS_B64) so nothing is hardcoded here. Every subsystem reads its
 * own key — Haptics gates on "haptics", GalaxyBackdropView on "stars_anim",
 * Home3DFragment on "cube_anim", LauncherStatusStripView on "pets_anim".
 */
class LauncherSettingsPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)

    var screensaver: String
        get() = sp.getString("screensaver", null) ?: Config.defaultScreensaverId
        set(v) { sp.edit().putString("screensaver", v).apply() }

    fun toggle(id: String): Boolean = sp.getBoolean("t_$id", Config.toggleDefault(id))
    fun setToggle(id: String, v: Boolean) { sp.edit().putBoolean("t_$id", v).apply() }

    var brightness: Int
        get() = sp.getInt("brightness", Config.brightness.default)
        set(v) { sp.edit().putInt("brightness", v).apply() }

    var eyeIntensity: Int
        get() = sp.getInt("eye_intensity", Config.eyeIntensity.default)
        set(v) { sp.edit().putInt("eye_intensity", v).apply() }

    // ── Data-driven metadata (for the picker UI + defaults) ──────────────
    data class Item(val id: String, val label: String, val subtitle: String, val default: Boolean)
    data class Slider(val label: String, val subtitle: String, val min: Int, val max: Int, val default: Int)

    object Config {
        private val settings: JSONObject by lazy {
            runCatching {
                JSONObject(String(Base64.decode(BuildConfig.UI_LAUNCHER_SETTINGS_B64, Base64.NO_WRAP)))
            }.getOrDefault(JSONObject())
        }

        val screensavers: List<Item> by lazy {
            runCatching {
                val arr = JSONArray(String(Base64.decode(BuildConfig.UI_SCREENSAVERS_B64, Base64.NO_WRAP)))
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Item(o.optString("id"), o.optString("label"), o.optString("subtitle"), o.optBoolean("default", false))
                }
            }.getOrDefault(emptyList())
        }
        val defaultScreensaverId: String
            get() = screensavers.firstOrNull { it.default }?.id ?: screensavers.firstOrNull()?.id ?: "black_clock"

        val toggles: List<Item> by lazy {
            val arr = settings.optJSONArray("toggles") ?: JSONArray()
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Item(o.optString("id"), o.optString("label"), o.optString("subtitle"), o.optBoolean("default", false))
            }
        }
        fun toggleDefault(id: String): Boolean = toggles.firstOrNull { it.id == id }?.default ?: true

        private fun slider(key: String, fallbackDefault: Int): Slider {
            val o = settings.optJSONObject(key) ?: JSONObject()
            return Slider(o.optString("label", key), o.optString("subtitle", ""),
                o.optInt("min", 0), o.optInt("max", 100), o.optInt("default", fallbackDefault))
        }
        val brightness: Slider by lazy { slider("brightness", -1) }
        val eyeIntensity: Slider by lazy { slider("eye_intensity", 35) }
    }
}
