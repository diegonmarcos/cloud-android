package com.diegonmarcos.superapp.updater

import android.content.Context

/**
 * The stub — compiled into every app that does NOT declare
 * :libs:shizuku-adb-debug-tools (News, Wallet, Nav, Calendar, Contacts,
 * Browser, Vault).
 *
 * That module drags BouncyCastle, Conscrypt, libadb-android and the Shizuku
 * client behind it: several MB of crypto and ADB machinery for a convenience
 * only the SuperApp's "Update all" ever performs. :libs:updater is linked into
 * all of them, so depending on it unconditionally made every satellite app pay
 * for it — and, because none of them declared the module, stopped them
 * configuring at all.
 *
 * "No channel" is a state Fleet.shellInstall already handles: it returns false
 * and falls back to the prompting PackageInstaller, exactly as it does when
 * Shizuku is installed but not granted. So these apps lose nothing they had.
 *
 * The members mirror only what Fleet actually calls. Keep them in step with
 * com.diegonmarcos.superapp.adbdebug.ShellChannel — the SuperApp build is the
 * check: it compiles Fleet against the real interface.
 */
internal interface ShellChannel {
    fun exec(ctx: Context, command: String): String?
    fun name(): String
}

internal fun activeShellChannel(@Suppress("UNUSED_PARAMETER") ctx: Context): ShellChannel? = null
