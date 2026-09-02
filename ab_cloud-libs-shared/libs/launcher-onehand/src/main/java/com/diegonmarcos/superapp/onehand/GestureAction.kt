package com.diegonmarcos.superapp.onehand

import android.accessibilityservice.AccessibilityService
import android.content.Context

/**
 * What a swipe does: either a global accessibility action, or launch an app by
 * package. Stored/serialized as a string — a bare action id ("back") or
 * "app:<package>" ("app:com.brave.browser"). This is the single value type for
 * both build.json defaults and per-swipe user overrides ([OneHandPrefs]).
 */
sealed class GestureAction {
    data class Global(val action: OneHandAction) : GestureAction()
    data class OpenApp(val pkg: String) : GestureAction()

    /**
     * A destination inside the HOST app — "action:open_search", "section:cloud",
     * a page target, a URL. Serialized "action:<target>".
     *
     * The gesture service runs outside any Activity, so it cannot call the
     * launcher directly. It launches the host app with the target in the
     * `shortcut_action` extra, which MainActivity.handleShortcutIntent already
     * routes — the same door the launcher long-press shortcuts come through.
     * So this variant adds a new WAY IN and no new dispatch: search opened from
     * the edge is the identical sheet the star opens.
     */
    data class AppTarget(val target: String) : GestureAction()

    /** Round-trips through [OneHandPrefs] and matches spinner selections. */
    fun serialize(): String = when (this) {
        is Global -> action.name
        is OpenApp -> "app:$pkg"
        is AppTarget -> "action:$target"
    }

    fun perform(svc: AccessibilityService) {
        when (this) {
            is Global -> if (action.supported) svc.performGlobalAction(action.globalAction)
            is OpenApp -> launch(svc, pkg)
            is AppTarget -> openInHost(svc, target)
        }
    }

    companion object {
        fun parse(s: String?): GestureAction? {
            if (s.isNullOrBlank()) return null
            if (s.startsWith("app:")) return OpenApp(s.removePrefix("app:"))
            // "action:" before the bare-id lookup: no OneHandAction is spelled
            // with a colon, so the prefixed form can never be a global action.
            if (s.startsWith("action:")) return AppTarget(s.removePrefix("action:"))
            return OneHandAction.from(s)?.let { Global(it) }
        }

        /** Wake the host app on the given target. [Context.getPackageName] is
         *  the host, so this stays correct for whichever app links the library
         *  rather than hardcoding the SuperApp. */
        private fun openInHost(ctx: Context, target: String) {
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("shortcut_action", if (target.contains(':')) target else "action:$target")
            runCatching { ctx.startActivity(intent) }
        }

        private fun launch(ctx: Context, pkg: String) {
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
        }
    }
}
