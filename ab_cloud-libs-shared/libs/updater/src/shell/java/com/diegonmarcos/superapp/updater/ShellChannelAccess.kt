package com.diegonmarcos.superapp.updater

import android.content.Context
import com.diegonmarcos.superapp.adbdebug.ShellChannel
import com.diegonmarcos.superapp.adbdebug.ShellChannels

/**
 * The real thing — compiled only into apps whose build.json declares
 * :libs:shizuku-adb-debug-tools (today: SuperApp). See the noshell/ twin and
 * the source-set switch in build.gradle for why this is a source set rather
 * than a plain dependency.
 *
 * This is a compile-time reference on purpose: renaming ShellChannels.active
 * breaks the SuperApp build loudly, which reflection would not.
 */
internal fun activeShellChannel(ctx: Context): ShellChannel? = ShellChannels.active(ctx)
