package com.diegonmarcos.superapp

import android.content.Context

/**
 * Tracks which dangerous permissions the user has been *asked* for at
 * least once. Used to disambiguate Android's two distinct "denied"
 * states — both report `shouldShowRequestPermissionRationale == false`
 * after a `checkSelfPermission == DENIED`, but they mean very
 * different things:
 *
 *  • **Never asked** — first launch / clean install; the next request
 *    call will pop the system dialog with both "Allow" and "Deny"
 *    visible.
 *  • **Permanently denied** — user previously picked "Don't ask
 *    again" (or denied twice on API 30+), and the next request call
 *    will be a no-op. The only path to grant is Settings → App info.
 *
 * Android exposes no API to tell those apart, so we record every
 * permission we hand to ActivityCompat.requestPermissions /
 * RequestMultiplePermissions in a SharedPreferences string-set; once a
 * permission is in the set, "no rationale + denied" == "permanently
 * denied (don't ask again)" instead of "never asked".
 */
class PermAskTracker(context: Context) {
    private val sp = context
        .getSharedPreferences("perm_ask_tracker", Context.MODE_PRIVATE)

    fun hasBeenRequested(perm: String): Boolean =
        sp.getStringSet(KEY, emptySet())?.contains(perm) == true

    fun markAsked(perm: String) {
        val cur = sp.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        if (cur.add(perm)) sp.edit().putStringSet(KEY, cur).apply()
    }

    fun markAskedAll(perms: Collection<String>) {
        val cur = sp.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        if (cur.addAll(perms)) sp.edit().putStringSet(KEY, cur).apply()
    }

    companion object { private const val KEY = "asked_set" }
}
