package com.diegonmarcos.superapp.kdeconnect

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

internal fun granted(ctx: Context, perm: String): Boolean =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
