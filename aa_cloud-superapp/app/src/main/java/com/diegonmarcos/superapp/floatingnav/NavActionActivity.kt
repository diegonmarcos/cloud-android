package com.diegonmarcos.superapp.floatingnav

import android.app.Activity
import android.os.Bundle

/**
 * Invisible trampoline for the notification action buttons. A notification
 * action that starts a *service* leaves the shade open; one that launches an
 * *activity* collapses it. So the actions point here: this activity performs
 * the action (via FloatingNavService) and finishes immediately — which closes
 * the Android notification shade, as the user wants. NoDisplay theme + finish
 * in onCreate = nothing visible.
 */
class NavActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(EXTRA_TARGET)?.let { FloatingNavService.runAction(this, it) }
        finish()
    }

    companion object {
        const val EXTRA_TARGET = "target"
    }
}
