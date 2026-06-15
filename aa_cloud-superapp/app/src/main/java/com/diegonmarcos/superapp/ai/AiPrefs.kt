package com.diegonmarcos.superapp.ai

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

    enum class Slot(
        val key: String,
        val defaultName: String,
        val role: String,
        val defaultApiUrl: String,
        val defaultCostCap: String,
    ) {
        S4B(           "4b",          "llama-3.1-4b-q4",  "Servicer",            "http://10.0.0.1:11434/v1",          "5"),
        S70B(          "70b",         "llama-3.3-70b-q4", "Tasks",               "http://10.0.0.1:11434/v1",          "10"),
        FRONTIER(      "frontier",    "claude-opus-4.7",  "Architecture",        "https://api.anthropic.com/v1",      "50"),
        FRONTIER_BATCH("frontierBatch","claude-opus-4.7", "Architecture · Batch","https://api.anthropic.com/v1/batches","30"),
    }

    fun name(slot: Slot): String =
        sp.getString("${slot.key}.name", slot.defaultName) ?: slot.defaultName

    fun setName(slot: Slot, value: String) {
        sp.edit().putString("${slot.key}.name", value).apply()
    }

    fun apiUrl(slot: Slot): String =
        sp.getString("${slot.key}.apiUrl", slot.defaultApiUrl) ?: slot.defaultApiUrl

    fun setApiUrl(slot: Slot, value: String) {
        sp.edit().putString("${slot.key}.apiUrl", value).apply()
    }

    fun token(slot: Slot): String =
        sp.getString("${slot.key}.token", "") ?: ""

    fun setToken(slot: Slot, value: String) {
        sp.edit().putString("${slot.key}.token", value).apply()
    }

    /** Per-month cost cap in USD. Stored as plain string so the user can
     *  type "5", "5.50", "" (no cap), etc. without input-type fighting. */
    fun costCap(slot: Slot): String =
        sp.getString("${slot.key}.costCap", slot.defaultCostCap) ?: slot.defaultCostCap

    fun setCostCap(slot: Slot, value: String) {
        sp.edit().putString("${slot.key}.costCap", value).apply()
    }

    /** When true the monthly cap is enforced as a per-day budget
     *  (cap ÷ days_in_month). Lets short bursts not eat the whole
     *  month's allowance early. Defaults off so existing behaviour
     *  (one bucket per month) is unchanged. */
    fun dailyCap(slot: Slot): Boolean =
        sp.getBoolean("${slot.key}.dailyCap", false)

    fun setDailyCap(slot: Slot, value: Boolean) {
        sp.edit().putBoolean("${slot.key}.dailyCap", value).apply()
    }
}
