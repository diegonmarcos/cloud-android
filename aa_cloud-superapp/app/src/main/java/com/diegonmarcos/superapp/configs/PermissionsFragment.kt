package com.diegonmarcos.superapp.configs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.appstore.ConstellationWorker
import com.diegonmarcos.superapp.battery.EnergyWatchdog
import com.diegonmarcos.superapp.floatingnav.FloatingNavService
import com.diegonmarcos.superapp.health.HealthConnectGateway
import com.diegonmarcos.superapp.health.HealthMetrics
import com.diegonmarcos.superapp.system.PermAskTracker
import com.diegonmarcos.superapp.system.ScreenLocker
import kotlinx.coroutines.launch

/** Permissions page — runtime perms, special access, Health Connect,
 *  auto-granted, and all grant/set action buttons. Extracted from
 *  DevControlFragment so it has its own dedicated Configs tab. */
class PermissionsFragment : Fragment() {

    private val notifPermLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
            Toast.makeText(requireContext(),
                if (it) "Notifications: granted" else "Notifications: denied",
                Toast.LENGTH_SHORT).show()
            rebuildFragment()
        }

    private val allPermsLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.count { it.value }
            val denied  = result.size - granted
            Toast.makeText(requireContext(),
                "Permissions: $granted granted, $denied denied", Toast.LENGTH_SHORT).show()
            rebuildFragment()
        }

    private fun rebuildFragment() {
        parentFragmentManager.beginTransaction().detach(this).commitNow()
        parentFragmentManager.beginTransaction().attach(this).commitNow()
    }

    private fun ctxAny(): Context = requireContext()

    companion object {
        fun newInstance() = PermissionsFragment()
        private val DARK_VIOLET = 0xFF4C1D95.toInt()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(col)

        var hcGrantBtn: TextView? = null
        val perms = parseRuntimePermissions()

        col.addView(small(ctx, "Runtime perms — States: ✓ Granted · ⏳ Ask each time · ✗ Denied (don't ask) · ◯ Not requested. Restricted-by-policy perms (SMS, Phone, Call Log) often stay at ✗ Denied (don't ask) on non-default handlers — toggle via system settings."))
        for ((label, perm) in perms) row(ctx, col, label, permissionState(perm))

        col.addView(small(ctx, "Special access — system-toggles outside the runtime perms flow:"))
        row(ctx, col, "Battery Optimization",      specialAccessBattery(ctxAny()))
        row(ctx, col, "Default launcher",           specialAccessLauncher(ctxAny()))
        for (r in parsePermissionRoles()) row(ctx, col, r.label, specialAccessRole(ctxAny(), r.role, r.expectedHolders))
        row(ctx, col, "Usage stats",                specialAccessUsageStats(ctxAny()))
        row(ctx, col, "Notif. listener",            specialAccessNotifListener(ctxAny()))
        row(ctx, col, "Manage all files",           specialAccessManageStorage())
        row(ctx, col, "Display over apps",          specialAccessOverlay(ctxAny()))
        row(ctx, col, "Modify system settings",     specialAccessWriteSettings(ctxAny()))
        row(ctx, col, "Dumpsys (DUMP)",             specialAccessDump(ctxAny()))
        if (!specialAccessDumpGranted(ctxAny())) {
            col.addView(actionButton(ctx, "Copy DUMP grant command for adb") {
                val cmd = "adb shell pm grant ${ctxAny().packageName} android.permission.DUMP"
                copy(ctxAny(), cmd)
                Toast.makeText(ctxAny(), "Copied — paste into a shell with adb access", Toast.LENGTH_LONG).show()
            })
        }

        col.addView(small(ctx, "Home double-tap → lock screen. PREFERRED path is Accessibility — it preserves Smart Lock (Garmin watch unlock, Trusted Place) and fingerprint / face. Device Admin is a fallback that disables those until you PIN-unlock once."))
        row(ctx, col, "Lock-screen accessibility (preferred)", ScreenLocker.statusStringAccessibility(ctxAny()))
        if (!ScreenLocker.isAccessibilityEnabled(ctxAny())) {
            col.addView(small(ctx,
                "Samsung blocks Accessibility for sideloaded apps via 'Restricted settings'.\n" +
                    "  1) Tap 'Open App Info' below.\n" +
                    "  2) Tap the ⋮ menu (top-right) → 'Allow restricted settings'.\n" +
                    "  3) Then come back + tap 'Open Accessibility settings' below to enable Cloud SuperApp."))
            col.addView(actionButton(ctx, "1) Set App Info (allow restricted settings)") { ScreenLocker.openAppInfo(ctxAny()) })
            col.addView(actionButton(ctx, "2) Set Accessibility — enable Cloud SuperApp") { ScreenLocker.openSystemAccessibilitySettings(ctxAny()) })
        } else {
            col.addView(actionButton(ctx, "Set Accessibility (revoke)") { ScreenLocker.openSystemAccessibilitySettings(ctxAny()) })
        }
        row(ctx, col, "Device admin (lock — fallback)", ScreenLocker.statusString(ctxAny()))
        if (!ScreenLocker.isActive(ctxAny())) {
            col.addView(actionButton(ctx, "Enable Device Admin (fallback — disables Smart Lock)") { ScreenLocker.requestActivation(requireActivity()) })
        } else {
            col.addView(actionButton(ctx, "Set Device Admin (revoke)") { ScreenLocker.openSystemDeviceAdminSettings(ctxAny()) })
        }

        col.addView(small(ctx, "Health Connect perms — granted in the Health Connect app, NOT here. checkSelfPermission can't see them; we query HC's PermissionController directly."))
        val hcTotal = HealthMetrics.allPermissions.size
        val hcRow = row(ctx, col, "HC perms granted", "checking… / $hcTotal")
        viewLifecycleOwner.lifecycleScope.launch {
            val n = runCatching { HealthConnectGateway.grantedPermissions(requireContext()).size }.getOrDefault(0)
            hcRow.text = if (n > 0) "✓ $n / $hcTotal granted" else "◯ 0 / $hcTotal — none granted"
            hcGrantBtn?.let { b -> stylePermButton(b, "Grant Health Perms", n >= hcTotal && hcTotal > 0) }
        }

        col.addView(small(ctx, "System auto-granted — protection-NORMAL perms granted at install, no user prompt. This is what the app can already do without ever asking."))
        for ((label, status) in collectAutoGrantedPerms(ctxAny())) row(ctx, col, label, status)

        col.addView(small(ctx, "Grant — one-tap system dialog:"))
        col.addView(permButtonRow(ctx,
            permButton(ctx, "Grant Health Perms", null) { openHealthConnectPerms() }.also { b -> hcGrantBtn = b },
            permButton(ctx, "Grant Notif. (write)", grantedNotifWrite(ctxAny())) { requestNotificationsPermission() },
            permButton(ctx, "Request All Perms", null) { requestAllPermissions(perms.map { p -> p.second }.toTypedArray()) },
        ))
        col.addView(small(ctx, "Set — open the menu and toggle manually:"))
        col.addView(permButtonRow(ctx,
            permButton(ctx, "Set Notif. (read)", grantedNotifRead(ctxAny())) { openNotificationListenerSettings() },
            permButton(ctx, "Set Usage Access", EnergyWatchdog.hasUsageAccess(ctxAny())) { openUsageAccessSettings() },
            permButton(ctx, "Set Files Access", grantedFiles()) { openManageAllFilesSettings() },
        ))
        col.addView(permButtonRow(ctx,
            permButton(ctx, "Set Battery No-Optim", grantedBatteryOptim(ctxAny())) { openBatteryOptimizationSettings() },
            permButton(ctx, "Set Samsung Never-Sleep", null) { openSamsungNeverSleepingSettings() },
            permButton(ctx, "Set App Settings", null) { openAppSettings() },
        ))
        col.addView(small(ctx, "Set defaults — pick the Cloud-Comms phone fork as Phone app + Caller ID & spam app:"))
        col.addView(permButtonRow(ctx,
            permButton(ctx, "Set Default Apps (Phone · Spam)", null) { openDefaultAppsSettings() },
        ))

        col.addView(small(ctx, "Floating nav — grant 'Display over other apps', then toggle the overlay:"))
        lateinit var navToggle: TextView
        navToggle = permButton(ctx, "Floating Nav", null) { toggleFloatingNav(navToggle) }
        styleNavToggle(navToggle, FloatingNavService.isRunning)
        col.addView(permButtonRow(ctx,
            permButton(ctx, "Set Display-over-apps", android.provider.Settings.canDrawOverlays(ctxAny())) { openOverlaySettings() },
            permButton(ctx, "Set Modify-system-settings", android.provider.Settings.System.canWrite(ctxAny())) { openWriteSettings() },
            navToggle,
        ))

        col.addView(small(ctx, "Auto-update — automatic app updates (default ON). Grant 'Install unknown apps' below for no-tap installs:"))
        row(ctx, col, "Auto-update",
            if (com.diegonmarcos.superapp.updater.AutoUpdatePrefs.enabled(ctxAny())) "✓ ON" else "✗ OFF")
        row(ctx, col, "Install unknown apps",
            if (com.diegonmarcos.superapp.updater.AutoUpdatePrefs.canInstallSilently(ctxAny())) "✓ Granted" else "◯ Not granted")
        col.addView(permButtonRow(ctx,
            permButton(ctx, "Auto-update: " + (if (com.diegonmarcos.superapp.updater.AutoUpdatePrefs.enabled(ctxAny())) "ON" else "OFF"),
                       com.diegonmarcos.superapp.updater.AutoUpdatePrefs.enabled(ctxAny())) {
                val now = !com.diegonmarcos.superapp.updater.AutoUpdatePrefs.enabled(ctxAny())
                com.diegonmarcos.superapp.updater.AutoUpdatePrefs.setEnabled(ctxAny(), now)
                com.diegonmarcos.superapp.updater.Updater.start(ctxAny())
                ConstellationWorker.start(ctxAny())
                Toast.makeText(ctxAny(), "Auto-update " + (if (now) "ON" else "OFF"), Toast.LENGTH_SHORT).show()
                rebuildFragment()
            },
            permButton(ctx, "Set Install-unknown-apps",
                       com.diegonmarcos.superapp.updater.AutoUpdatePrefs.canInstallSilently(ctxAny())) { openUnknownAppSourcesSettings() },
        ))
        col.addView(actionButton(ctx, "Copy All Perms Status", DARK_VIOLET) {
            copy(ctxAny(), buildAllPermsStatus(ctxAny()))
            Toast.makeText(ctxAny(), "Copied full permission status", Toast.LENGTH_SHORT).show()
        })

        return scroll
    }

    // ── UI helpers ────────────────────────────────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun row(ctx: Context, host: LinearLayout, key: String, value: String): TextView {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        r.addView(TextView(ctx).apply {
            text = key
            setTextColor(0xCCFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            layoutParams = LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val v = TextView(ctx).apply {
            text = value
            setTextColor(0xFFB794F4.toInt())
            typeface = Typeface.MONOSPACE
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnLongClickListener {
                copy(ctx, "$key: $value")
                Toast.makeText(ctx, "Copied $key", Toast.LENGTH_SHORT).show(); true
            }
        }
        r.addView(v)
        host.addView(r)
        return v
    }

    private fun small(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(0x99FFFFFF.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun actionButton(ctx: Context, label: String, bg: Int = 0xFF7C3AED.toInt(), onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(bg)
        gravity = android.view.Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun permButton(ctx: Context, label: String, granted: Boolean?, onClick: () -> Unit) =
        TextView(ctx).apply {
            gravity = android.view.Gravity.CENTER
            textSize = 12f
            setPadding(dp(8), dp(10), dp(8), dp(10))
            maxLines = 3
            minHeight = dp(64)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true; isFocusable = true
            stylePermButton(this, label, granted)
            setOnClickListener { onClick() }
        }

    private fun stylePermButton(tv: TextView, label: String, granted: Boolean?) {
        tv.text = if (granted == true) "✓ $label" else label
        tv.setTextColor(if (granted == true) 0xFF9CA3AF.toInt() else 0xFFFFFFFF.toInt())
        tv.setBackgroundColor(if (granted == true) 0xFF2A2A33.toInt() else 0xFF7C3AED.toInt())
    }

    private fun styleNavToggle(tv: TextView, running: Boolean) {
        tv.text = if (running) "Stop Floating Nav" else "Start Floating Nav"
        tv.setTextColor(if (running) 0xFF9CA3AF.toInt() else 0xFFFFFFFF.toInt())
        tv.setBackgroundColor(if (running) 0xFF2A2A33.toInt() else 0xFF7C3AED.toInt())
    }

    private fun permButtonRow(ctx: Context, vararg btns: View): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        for ((i, b) in btns.withIndex()) {
            (b.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = if (i > 0) dp(4) else 0
            row.addView(b)
        }
        return row
    }

    private fun copy(ctx: Context, v: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("perms", v))
    }

    // ── Permission request ────────────────────────────────────────────

    private fun requestNotificationsPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            PermAskTracker(requireContext()).markAsked("android.permission.POST_NOTIFICATIONS")
            notifPermLauncher.launch("android.permission.POST_NOTIFICATIONS")
        } else {
            Toast.makeText(requireContext(), "Pre-API 33 — notifications granted by default", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestAllPermissions(perms: Array<String>) {
        PermAskTracker(requireContext()).markAskedAll(perms.toList())
        allPermsLauncher.launch(perms)
    }

    private fun permissionState(perm: String): String {
        val ctx = ctxAny()
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, perm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return "✓ Granted"
        val act = activity ?: return "✗ Denied"
        val rationale = runCatching {
            androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(act, perm)
        }.getOrDefault(false)
        if (rationale) return "⏳ Ask each time"
        val askedBefore = PermAskTracker(ctx).hasBeenRequested(perm)
        return if (askedBefore) "✗ Denied (don't ask)" else "◯ Not requested"
    }

    // ── Settings openers ─────────────────────────────────────────────

    private fun openAppSettings() {
        runCatching {
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", requireContext().packageName, null),
            ))
        }
    }

    private fun openUsageAccessSettings() {
        runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }

    private fun openOverlaySettings() {
        val ctx = requireContext()
        val scoped = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.fromParts("package", ctx.packageName, null))
        if (scoped.resolveActivity(ctx.packageManager) != null) { runCatching { startActivity(scoped) }; return }
        val list = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        if (list.resolveActivity(ctx.packageManager) != null) { runCatching { startActivity(list) }; return }
        runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", ctx.packageName, null))) }
    }

    private fun openUnknownAppSourcesSettings() {
        val ctx = requireContext()
        val scoped = android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.fromParts("package", ctx.packageName, null))
        if (scoped.resolveActivity(ctx.packageManager) != null) { runCatching { startActivity(scoped) }; return }
        val list = android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        if (list.resolveActivity(ctx.packageManager) != null) { runCatching { startActivity(list) }; return }
        runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", ctx.packageName, null))) }
    }

    private fun openDefaultAppsSettings() {
        val ctx = requireContext()
        val i = android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        if (i.resolveActivity(ctx.packageManager) != null) { runCatching { startActivity(i) }; return }
        runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) }
    }

    private fun openBatteryOptimizationSettings() {
        val i = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (i.resolveActivity(requireContext().packageManager) != null) { runCatching { startActivity(i) }; return }
        runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", requireContext().packageName, null))) }
    }

    private fun openManageAllFilesSettings() {
        val ctx = requireContext()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val scoped = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.fromParts("package", ctx.packageName, null))
            if (scoped.resolveActivity(ctx.packageManager) != null) { runCatching { startActivity(scoped) }; return }
            val list = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            if (list.resolveActivity(ctx.packageManager) != null) { runCatching { startActivity(list) }; return }
        }
        runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", ctx.packageName, null))) }
    }

    private fun openSamsungNeverSleepingSettings() {
        val ctx = requireContext()
        for (intent in listOf(
            android.content.Intent("com.samsung.android.sm.ACTION_BACKGROUND_USAGE_LIMITS"),
            android.content.Intent().setComponent(android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
            android.content.Intent().setComponent(android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            android.content.Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS),
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null)),
        )) {
            if (intent.resolveActivity(ctx.packageManager) != null) { runCatching { startActivity(intent) }; return }
        }
    }

    private fun openNotificationListenerSettings() {
        val i = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        if (i.resolveActivity(requireContext().packageManager) != null) { runCatching { startActivity(i) }; return }
        runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", requireContext().packageName, null))) }
    }

    private fun openWriteSettings() {
        runCatching {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                android.net.Uri.parse("package:" + ctxAny().packageName)))
        }.onFailure {
            runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)) }
        }
    }

    private fun openHealthConnectPerms() {
        val tried = sequenceOf(
            android.content.Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"),
            android.content.Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"),
        ).mapNotNull { intent -> runCatching { startActivity(intent); intent }.getOrNull() }.firstOrNull()
        if (tried == null) {
            runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("market://details?id=com.google.android.apps.healthdata"))) }
        }
    }

    private fun toggleFloatingNav(tv: TextView) {
        if (FloatingNavService.isRunning) {
            FloatingNavService.stop(ctxAny())
            Toast.makeText(ctxAny(), "Floating nav stopped", Toast.LENGTH_SHORT).show()
            styleNavToggle(tv, running = false)
        } else {
            val ok = FloatingNavService.startIfPermitted(ctxAny())
            Toast.makeText(ctxAny(),
                if (ok) "Floating nav started" else "Grant 'Display over other apps' first",
                Toast.LENGTH_SHORT).show()
            styleNavToggle(tv, running = ok)
        }
    }

    // ── Grant predicates ─────────────────────────────────────────────

    private fun grantedNotifWrite(ctx: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 33)
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else true

    private fun grantedNotifRead(ctx: Context): Boolean = runCatching {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }.getOrDefault(false)

    private fun grantedFiles(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager() else true

    private fun grantedBatteryOptim(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

    // ── Special-access status ─────────────────────────────────────────

    private fun specialAccessBattery(ctx: Context): String = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true) "✓ Whitelisted (no Doze)" else "◯ Subject to Doze"
    } catch (_: Throwable) { "—" }

    private fun specialAccessLauncher(ctx: Context): String = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
            if (rm?.isRoleHeld(android.app.role.RoleManager.ROLE_HOME) == true) "✓ Default home/launcher" else "◯ Not default"
        } else "— (pre-API 29)"
    } catch (_: Throwable) { "—" }

    private data class RoleSpec(val label: String, val role: String, val expectedHolders: List<String>)

    private fun specialAccessRole(ctx: Context, role: String, expected: List<String>): String = try {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) "— (pre-API 29)"
        else {
            val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
            when {
                rm?.isRoleAvailable(role) != true -> "— (unsupported)"
                role == android.app.role.RoleManager.ROLE_DIALER -> {
                    val tm = ctx.getSystemService(android.telecom.TelecomManager::class.java)
                    val holder = runCatching { tm?.defaultDialerPackage }.getOrNull()
                    when {
                        holder.isNullOrBlank()    -> "◯ none — set in Default apps"
                        expected.contains(holder) -> "✓ $holder"
                        else                      -> "◯ $holder — set in Default apps"
                    }
                }
                rm.isRoleHeld(role) -> "✓ held by this app"
                else                -> "◯ set in Default apps"
            }
        }
    } catch (_: Throwable) { "—" }

    private fun specialAccessOverlay(ctx: Context): String = try {
        if (android.provider.Settings.canDrawOverlays(ctx)) "✓ Allowed" else "◯ Not allowed"
    } catch (_: Throwable) { "—" }

    private fun specialAccessWriteSettings(ctx: Context): String = try {
        if (android.provider.Settings.System.canWrite(ctx)) "✓ Allowed" else "◯ Not allowed"
    } catch (_: Throwable) { "—" }

    private fun specialAccessUsageStats(ctx: Context): String = try {
        val aom = ctx.getSystemService(android.app.AppOpsManager::class.java)
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            aom?.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        else
            @Suppress("DEPRECATION") aom?.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        when (mode) {
            android.app.AppOpsManager.MODE_ALLOWED -> "✓ Allowed"
            null -> "—"
            else -> "◯ Not allowed"
        }
    } catch (_: Throwable) { "—" }

    private fun specialAccessNotifListener(ctx: Context): String = try {
        val flat = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        if (flat.split(":").any { it.startsWith("${ctx.packageName}/") }) "✓ Allowed" else "◯ Not allowed"
    } catch (_: Throwable) { "—" }

    private fun specialAccessDumpGranted(ctx: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, "android.permission.DUMP") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun specialAccessDump(ctx: Context): String =
        if (specialAccessDumpGranted(ctx)) "✓ Granted (adb)" else "◯ Not granted — needs one-time adb pm grant"

    private fun specialAccessManageStorage(): String = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            if (android.os.Environment.isExternalStorageManager()) "✓ Allowed (R+)" else "◯ Not allowed (R+)"
        else "— (pre-API 30 — uses storage perms)"
    } catch (_: Throwable) { "—" }

    // ── Data parsers ──────────────────────────────────────────────────

    private fun parseRuntimePermissions(): List<Pair<String, String>> {
        val raw = runCatching {
            String(android.util.Base64.decode(BuildConfig.UI_PERMISSIONS_RUNTIME_B64, android.util.Base64.DEFAULT))
        }.getOrDefault("[]")
        val arr = runCatching { org.json.JSONArray(raw) }.getOrDefault(org.json.JSONArray())
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val label = o.optString("label"); val perm = o.optString("perm")
            if (label.isBlank() || perm.isBlank()) continue
            out.add(label to perm)
        }
        return out
    }

    private fun parsePermissionRoles(): List<RoleSpec> {
        val raw = runCatching {
            String(android.util.Base64.decode(BuildConfig.UI_PERMISSIONS_ROLES_B64, android.util.Base64.DEFAULT))
        }.getOrDefault("[]")
        val arr = runCatching { org.json.JSONArray(raw) }.getOrDefault(org.json.JSONArray())
        val out = mutableListOf<RoleSpec>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val label = o.optString("label"); val role = o.optString("role")
            if (label.isBlank() || role.isBlank()) continue
            val holdersArr = o.optJSONArray("expected_holders")
            val holders = mutableListOf<String>()
            if (holdersArr != null) for (j in 0 until holdersArr.length()) {
                val h = holdersArr.optString(j); if (h.isNotBlank()) holders.add(h)
            }
            out.add(RoleSpec(label, role, holders))
        }
        return out
    }

    private fun collectAutoGrantedPerms(ctx: Context): List<Pair<String, String>> = try {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags ?: IntArray(requested.size)
        val out = mutableListOf<Pair<String, String>>()
        for ((i, perm) in requested.withIndex()) {
            val grantedAtInstall = (flags.getOrNull(i) ?: 0) and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            if (!grantedAtInstall) continue
            val info2 = runCatching { pm.getPermissionInfo(perm, 0) }.getOrNull()
            val base = (info2?.protectionLevel ?: -1) and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE
            if (base == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS) continue
            val tag = when (base) {
                android.content.pm.PermissionInfo.PROTECTION_NORMAL    -> "NORMAL"
                android.content.pm.PermissionInfo.PROTECTION_SIGNATURE -> "SIGNATURE"
                else -> "?"
            }
            out.add(perm.removePrefix("android.permission.").take(36) to "✓ auto · $tag")
        }
        out
    } catch (_: Throwable) { emptyList() }

    private fun buildAllPermsStatus(ctx: Context): String = buildString {
        appendLine("Cloud SuperApp — permission status")
        appendLine("pkg: ${ctx.packageName}")
        appendLine()
        appendLine("== Runtime ==")
        for ((label, perm) in parseRuntimePermissions()) appendLine("$label: ${permissionState(perm)}")
        appendLine()
        appendLine("== Special access ==")
        appendLine("Battery Optimization: ${specialAccessBattery(ctx)}")
        appendLine("Default launcher: ${specialAccessLauncher(ctx)}")
        for (r in parsePermissionRoles()) appendLine("${r.label}: ${specialAccessRole(ctx, r.role, r.expectedHolders)}")
        appendLine("Usage stats: ${specialAccessUsageStats(ctx)}")
        appendLine("Notif. listener: ${specialAccessNotifListener(ctx)}")
        appendLine("Manage all files: ${specialAccessManageStorage()}")
        appendLine("Display over apps: ${specialAccessOverlay(ctx)}")
        appendLine("Modify system settings: ${specialAccessWriteSettings(ctx)}")
        appendLine("Dumpsys (DUMP): ${specialAccessDump(ctx)}")
        appendLine("Lock-screen accessibility: ${ScreenLocker.statusStringAccessibility(ctx)}")
        appendLine("Device admin (lock): ${ScreenLocker.statusString(ctx)}")
        appendLine()
        appendLine("== Auto-granted (NORMAL) ==")
        for ((label, status) in collectAutoGrantedPerms(ctx)) appendLine("$label: $status")
    }
}
