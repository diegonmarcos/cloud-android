package com.diegonmarcos.superapp

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-model name + token storage for the Configs · AI page. Three
 * slots covering the user's local-model tier (4b/70b on a server) plus
 * a frontier-model architecture slot.
 *
 * Tokens are stored as plain SharedPreferences strings — same threat
 * model as ProfilePrefs / WireGuardPrefs (a phone with screen-unlock
 * is already root-equivalent for the user's perspective). Real
 * secrets-management lives in the cloud-side vault.
 *
 * First-run defaults come from BuildConfig.UI_AI_* if defined; for now
 * baseline names ship via the data class fall-backs.
 */
class AiPrefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

    enum class Slot(val key: String, val defaultName: String, val role: String) {
        S4B(  "4b",       "llama-3.1-4b-q4",   "Server"),
        S70B( "70b",      "llama-3.3-70b-q4",  "Tasks"),
        FRONTIER("frontier", "claude-opus-4.7", "Architecture"),
    }

    fun name(slot: Slot): String =
        sp.getString("${slot.key}.name", slot.defaultName) ?: slot.defaultName

    fun setName(slot: Slot, value: String) {
        sp.edit().putString("${slot.key}.name", value).apply()
    }

    fun token(slot: Slot): String =
        sp.getString("${slot.key}.token", "") ?: ""

    fun setToken(slot: Slot, value: String) {
        sp.edit().putString("${slot.key}.token", value).apply()
    }
}
