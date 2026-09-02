package com.diegonmarcos.superapp.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Boot hook for the Shizuku-independent privileged plane. A receiver gets
 * ~10s and Wireless Debugging + mDNS need longer than that after boot, so
 * all the work lives in [PrivilegedPlaneWorker].
 */
class PrivilegedPlaneBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<PrivilegedPlaneWorker>().build()
        )
    }
}
