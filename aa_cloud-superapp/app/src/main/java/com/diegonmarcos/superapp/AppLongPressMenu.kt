package com.diegonmarcos.superapp
import com.diegonmarcos.superapp.battery.BatteryEstimatePopup

import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Stock-Android-style "long-press an app icon" context menu —
 * mirrors what Pixel Launcher / One UI Home / Niagara show when
 * the user holds on an app tile:
 *
 *   • App shortcuts (dynamic + static, as published by the app via
 *     LauncherApps.getShortcuts — chat apps surface "New message",
 *     calendar shows "New event", browsers show "New tab" + recent
 *     tabs, etc.)
 *   • App info       — jumps to Settings → Apps → ThisApp.
 *   • Uninstall      — fires ACTION_DELETE with the package URI.
 *
 * The menu is a vertically-stacked Dialog (Theme_Translucent_NoTitleBar)
 * with a dark-glass rounded container, mirroring the visual language
 * of BatteryEstimatePopup / SystemInfoPopup so the launcher feels
 * consistent across long-press surfaces. Tap outside → dismiss.
 *
 * Used by BOTH PhoneAppsFragment (Home Apps / Phone tab, expanded
 * folder dialog) and SuitePhoneAppsFragment (Suite / Phone grid +
 * folder modal). Single helper means the menu shape is identical
 * regardless of where the long-press happened.
 *
 * Permissions:
 *   • LauncherApps.getShortcuts(...) needs the calling app to be
 *     the default launcher OR to have been granted shortcuts
 *     access. The SuperApp ships with the HOME intent-filter
 *     (AndroidManifest.xml) so users who pick it as their home
 *     get shortcut access for free. Non-default-launcher install
 *     → the shortcuts query silently returns null and only the
 *     App info / Uninstall rows render. No crash, no warning.
 */
object AppLongPressMenu {

    /** Build + show the long-press menu for the given package. The
     *  caller is typically the long-press handler on a tile; we
     *  resolve user + componentName ourselves so the call site stays
     *  one-liner. */
    fun show(ctx: Context, pkg: String) {
        val launcher = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val me = Process.myUserHandle()
        val activityComponent = launcher?.getActivityList(pkg, me)?.firstOrNull()?.componentName
        val appLabel = runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)

        // Full-screen dim-tap-to-dismiss backdrop, with the menu
        // container centered. FrameLayout-like layering achieved
        // via LinearLayout(VERTICAL) + a centered child — keeps
        // touch dispatch simple (taps outside the container hit
        // the parent's click listener and dismiss).
        val backdrop = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }

        val d = ctx.resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(0xEE111111.toInt())
                setStroke(maxOf(1, (1 * d).toInt()), 0x44FFFFFF.toInt())
            }
            // Stop click propagation — taps INSIDE the menu must NOT
            // dismiss the dialog (backdrop's onClick would otherwise
            // fire whenever a non-clickable child got tapped).
            isClickable = true
            setOnClickListener { /* swallow */ }
            layoutParams = LinearLayout.LayoutParams(
                (280 * d).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // Header — app label.
        menu.addView(TextView(ctx).apply {
            text = appLabel
            setTextColor(0xFFE9D8FD.toInt())
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (10 * d).toInt())
        })

        // App shortcuts (dynamic + manifest). API 25+. Fails silently
        // when not the default launcher — see KDoc above.
        val shortcuts = queryShortcuts(launcher, pkg, me)
        for (s in shortcuts) {
            menu.addView(makeMenuRow(ctx, s.shortLabel?.toString() ?: s.id, "shortcut") {
                dialog.dismiss()
                runCatching {
                    launcher?.startShortcut(s, null, null)
                }
            })
        }
        if (shortcuts.isNotEmpty()) menu.addView(divider(ctx, d))

        // App info — Settings → Apps → ThisApp.
        menu.addView(makeMenuRow(ctx, "App info", "system") {
            dialog.dismiss()
            runCatching {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            }
        })

        // Uninstall — modern PackageInstaller.uninstall(): reliably raises the
        // system "Uninstall this app?" dialog across OEMs (ACTION_DELETE was
        // silently no-op on some devices). The shared PackageInstallerReceiver
        // forwards STATUS_PENDING_USER_ACTION → the confirm dialog and reports
        // the result. Needs REQUEST_DELETE_PACKAGES (declared in the manifest).
        menu.addView(makeMenuRow(ctx, "Uninstall", "system") {
            dialog.dismiss()
            requestUninstall(ctx, pkg)
        })

        backdrop.addView(menu)
        dialog.setContentView(backdrop)
        // Make the dialog fill the screen so the backdrop covers
        // everything including the status / nav bars (matching what
        // Pixel Launcher does — long-press menus dim the wallpaper).
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        dialog.show()
    }

    /** Fire the system uninstall flow for [pkg] via PackageInstaller.uninstall.
     *  The result callback goes to the shared PackageInstallerReceiver (which
     *  launches the confirm dialog and surfaces the outcome). Wrapped so a
     *  SecurityException (permission revoked) or a protected/system package
     *  can't crash the launcher. */
    private fun requestUninstall(ctx: Context, pkg: String) {
        runCatching {
            val installer = ctx.packageManager.packageInstaller
            val intent = Intent(ctx, com.diegonmarcos.superapp.updater.PackageInstallerReceiver::class.java)
                .setPackage(ctx.packageName)
                .putExtra(
                    com.diegonmarcos.superapp.updater.PackageInstallerReceiver.EXTRA_OP,
                    com.diegonmarcos.superapp.updater.PackageInstallerReceiver.OP_UNINSTALL,
                )
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_MUTABLE
            val pending = android.app.PendingIntent.getBroadcast(ctx, pkg.hashCode(), intent, flags)
            installer.uninstall(pkg, pending.intentSender)
        }.onFailure {
            android.widget.Toast.makeText(ctx, "Can't uninstall: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Query the dynamic + manifest shortcuts for the given package.
     *  Returns an empty list when the LauncherApps service isn't
     *  available OR when we don't have the privilege to query
     *  (non-default-launcher install). Never throws. */
    private fun queryShortcuts(launcher: LauncherApps?, pkg: String, me: UserHandle): List<ShortcutInfo> {
        if (launcher == null || Build.VERSION.SDK_INT < 25) return emptyList()
        return runCatching {
            val q = LauncherApps.ShortcutQuery()
                .setPackage(pkg)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
                )
            launcher.getShortcuts(q, me) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun makeMenuRow(ctx: Context, label: String, kind: String, onClick: () -> Unit): View {
        val d = ctx.resources.displayMetrics.density
        val row = TextView(ctx).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            isClickable = true; isFocusable = true
            val outVal = android.util.TypedValue()
            ctx.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, outVal, true)
            if (outVal.resourceId != 0) setBackgroundResource(outVal.resourceId)
            setOnClickListener { onClick() }
        }
        // Shortcut rows render slightly indented in lavender to
        // visually distinguish them from system actions.
        if (kind == "shortcut") {
            row.setTextColor(0xFFB794F4.toInt())
            row.setPadding((20 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }
        return row
    }

    private fun divider(ctx: Context, d: Float) = View(ctx).apply {
        setBackgroundColor(0x22FFFFFF.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, (1 * d).toInt())
        ).apply {
            topMargin = (6 * d).toInt(); bottomMargin = (6 * d).toInt()
        }
    }
}
