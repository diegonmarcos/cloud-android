package com.diegonmarcos.ide

import android.content.Context
import android.content.Intent

/**
 * Launches a fork app. Under the one-icon model the forks ship ICON-LESS (their
 * branding patch drops <category LAUNCHER>), so getLaunchIntentForPackage()
 * returns null for them — instead they declare an <intent-filter> for
 * BuildConfig.OPEN_FORK_ACTION (signature-gated), and the hub starts them via
 * Intent(action).setPackage(appId).
 *
 * The launcher tries the action first and falls back to a normal launch intent,
 * so a fork that is still icon-bearing (e.g. mid-migration, or before its
 * branding patch lands) keeps working. Returns false if neither resolves.
 */
object ForkLauncher {

    fun launch(ctx: Context, fork: Fork, configure: (Intent) -> Unit = {}): Boolean {
        val pm = ctx.packageManager
        val byAction = Intent(BuildConfig.OPEN_FORK_ACTION).setPackage(fork.appId)
        val intent = when {
            byAction.resolveActivity(pm) != null -> byAction
            else -> pm.getLaunchIntentForPackage(fork.appId) ?: return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        configure(intent)
        ctx.startActivity(intent)
        return true
    }
}
