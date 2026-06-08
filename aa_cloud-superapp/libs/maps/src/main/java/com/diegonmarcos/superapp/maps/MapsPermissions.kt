package com.diegonmarcos.superapp.maps

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Runtime-permission helper for the GPS tracker. Android splits the
 * location flow into three asks:
 *
 *   1. ACCESS_FINE_LOCATION       — runtime, foreground use.
 *   2. ACCESS_BACKGROUND_LOCATION — runtime, Android 10+ ONLY. Must
 *      be asked AFTER fine is granted (Google Play policy + Android
 *      system enforcement). Shows the user a system dialog with the
 *      "Allow all the time" radio.
 *   3. POST_NOTIFICATIONS         — runtime, Android 13+ ONLY. Needed
 *      for the foreground service's persistent notification.
 *
 * Helper exposes a single [missing] function returning the list of
 * still-needed permissions in the correct order. Callers should
 * request them in that order — never bundle FINE + BACKGROUND in one
 * dialog (BACKGROUND is silently denied if FINE isn't already
 * granted at request time).
 */
object MapsPermissions {

    fun missing(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            out += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            out += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            out += Manifest.permission.POST_NOTIFICATIONS
        }
        return out
    }

    /** True when every permission needed for foreground + background
     *  tracking has been granted. The tracker control fragment uses
     *  this to gate the Start/Stop button. */
    fun allGranted(ctx: Context): Boolean = missing(ctx).isEmpty()

    /** Fire the runtime request. Caller must implement
     *  [Activity.onRequestPermissionsResult] (or use the modern
     *  ActivityResultContracts.RequestMultiplePermissions). The
     *  helper just batches the OS call. */
    fun request(activity: Activity, perms: List<String>, requestCode: Int) {
        if (perms.isEmpty()) return
        ActivityCompat.requestPermissions(activity, perms.toTypedArray(), requestCode)
    }

    /** True when fine+background+post-notifications are ALL granted
     *  AND foreground service can run. Used by the service itself
     *  on startCommand to bail early if something's missing. */
    fun canTrackerRun(ctx: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val bg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            else true
        return fine && bg
    }
}
