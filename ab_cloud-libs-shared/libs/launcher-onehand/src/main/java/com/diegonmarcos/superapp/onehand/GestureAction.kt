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

    /** Round-trips through [OneHandPrefs] and matches spinner selections. */
    fun serialize(): String = when (this) {
        is Global -> action.name
        is OpenApp -> "app:$pkg"
    }

    fun perform(svc: AccessibilityService) {
        when (this) {
            is Global -> if (action.supported) svc.performGlobalAction(action.globalAction)
            is OpenApp -> launch(svc, pkg)
        }
    }

    companion object {
        fun parse(s: String?): GestureAction? {
            if (s.isNullOrBlank()) return null
            if (s.startsWith("app:")) return OpenApp(s.removePrefix("app:"))
            return OneHandAction.from(s)?.let { Global(it) }
        }

        private fun launch(ctx: Context, pkg: String) {
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
        }
    }
}
