package com.diegonmarcos.superapp.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives PackageInstaller status callbacks. On USER_ACTION_REQUIRED we
 * forward Android's confirmation Activity intent so the prompt shows up.
 */
class PackageInstallerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        Log.i("Updater/Receiver", "status=$status msg=$msg")
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android needs the user to tap "Install" — show the system dialog.
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(confirm)
        }
    }
}
