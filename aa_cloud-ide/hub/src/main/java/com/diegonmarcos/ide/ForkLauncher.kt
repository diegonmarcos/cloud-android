package com.diegonmarcos.ide

import android.content.Context
import android.content.Intent

/**
 * Launches one of our apps and raises the Cloud-IDE overlay nav bar over it
 * (the wrapper chrome — see NavOverlayService).
 *
 * Resolution order:
 *  1. OPEN_FORK action (our future patched, icon-less forks declare it).
 *  2. Standard launch intent for [Fork.launchPackage] — the path used TODAY by
 *     the embedded stock apps (com.foxdebug.acode / com.amaze.filemanager).
 *
 * Returns false if the app isn't installed (caller then installs from the
 * bundle via BundledForkInstaller).
 */
object ForkLauncher {

    fun launch(ctx: Context, fork: Fork, configure: (Intent) -> Unit = {}): Boolean {
        val pm = ctx.packageManager
        val byAction = Intent(BuildConfig.OPEN_FORK_ACTION).setPackage(fork.launchPackage)
        val intent = when {
            byAction.resolveActivity(pm) != null -> byAction
            else -> pm.getLaunchIntentForPackage(fork.launchPackage) ?: return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        configure(intent)
        ctx.startActivity(intent)
        // Wrapper chrome is OFF by default (Cloud-SuperApp owns the cross-app
        // bar) — only raise it when the user opted in via Configs.
        if (IdePrefs.overlayNavBar(ctx)) NavOverlayService.show(ctx, fork.launchPackage)
        return true
    }
}
