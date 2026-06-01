package com.diegonmarcos.superapp.devcontrol

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
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.updater.BuildConfig as UpdBuildConfig
import com.diegonmarcos.superapp.Sections
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * About page — comprehensive runtime + build metadata for the user
 * to inspect. Long-press any monospace row to copy to clipboard.
 */
class DevControlFragment : Fragment() {

    /** Standard permission request — wired to the Notifications button
     *  in the Permissions section. Re-renders the fragment on result so
     *  the row's ✓ / ✗ updates without a manual refresh. */
    private val notifPermLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
            Toast.makeText(requireContext(),
                if (it) "Notifications: granted" else "Notifications: denied",
                Toast.LENGTH_SHORT).show()
            // Rebuild this fragment so the perm row state refreshes.
            parentFragmentManager.beginTransaction()
                .detach(this).commitNow()
            parentFragmentManager.beginTransaction()
                .attach(this).commitNow()
        }

    private fun ctxAny(): Context = requireContext()

    private fun requestNotificationsPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notifPermLauncher.launch("android.permission.POST_NOTIFICATIONS")
        } else {
            Toast.makeText(requireContext(),
                "Pre-API 33 — notifications granted by default", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppSettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", requireContext().packageName, null),
        )
        startActivity(intent)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16); setPadding(pad, pad, pad, pad)
        }
        scroll.addView(column)

        column.addView(title(ctx, "About Cloud SuperApp"))

        section(ctx, column, "App") {
            row(ctx, it, "Name",         BuildConfig.APPLICATION_ID)
            row(ctx, it, "Version",      BuildConfig.VERSION_NAME)
            row(ctx, it, "Version code", BuildConfig.VERSION_CODE.toString())
            row(ctx, it, "Git sha",      BuildConfig.GIT_SHORT_SHA)
            row(ctx, it, "Built (UTC)",  BuildConfig.BUILD_TIMESTAMP)
            row(ctx, it, "Build type",   BuildConfig.BUILD_TYPE)
            row(ctx, it, "Debuggable",   BuildConfig.DEBUG.toString())
        }

        section(ctx, column, "Release / GHCR") {
            row(ctx, it, "Registry",  UpdBuildConfig.GHCR_REGISTRY)
            row(ctx, it, "Namespace", UpdBuildConfig.GHCR_NAMESPACE)
            row(ctx, it, "Image",     UpdBuildConfig.GHCR_IMAGE)
            row(ctx, it, "Tag",       UpdBuildConfig.AUTO_UPDATE_TAG)
            row(ctx, it, "Full URL",
                "${UpdBuildConfig.GHCR_REGISTRY}/${UpdBuildConfig.GHCR_NAMESPACE}/${UpdBuildConfig.GHCR_IMAGE}:${UpdBuildConfig.AUTO_UPDATE_TAG}")
            row(ctx, it, "Check interval", "${UpdBuildConfig.AUTO_UPDATE_INTERVAL_HOURS}h")
        }

        section(ctx, column, "APK") {
            val pm = requireContext().packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(requireContext().packageName, 0)
            val path = info.applicationInfo.sourceDir
            val size = runCatching { File(path).length() }.getOrDefault(0L)
            row(ctx, it, "Path",       path)
            row(ctx, it, "Size",       sizeStr(size))
            row(ctx, it, "Installed",  fmtMillis(info.firstInstallTime))
            row(ctx, it, "Updated",    fmtMillis(info.lastUpdateTime))
        }

        section(ctx, column, "Device / stack") {
            row(ctx, it, "Manufacturer", android.os.Build.MANUFACTURER)
            row(ctx, it, "Model",        android.os.Build.MODEL)
            row(ctx, it, "Brand",        android.os.Build.BRAND)
            row(ctx, it, "Device",       android.os.Build.DEVICE)
            row(ctx, it, "Hardware",     android.os.Build.HARDWARE)
            row(ctx, it, "Android",      "${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            row(ctx, it, "ABIs",         android.os.Build.SUPPORTED_ABIS.joinToString(", "))
            row(ctx, it, "Locale",       java.util.Locale.getDefault().toLanguageTag())
        }

        section(ctx, column, "Storage") {
            val ctxAny = requireContext()
            val pm = ctxAny.packageManager
            @Suppress("DEPRECATION")
            val pkg = pm.getPackageInfo(ctxAny.packageName, 0)
            val apkBytes  = runCatching { File(pkg.applicationInfo.sourceDir).length() }.getOrDefault(0L)
            // "Datos" = the app's private data root — includes filesDir,
            // databases, shared_prefs, and everything else the app
            // persists outside of cacheDir.
            val dataDir   = File(pkg.applicationInfo.dataDir)
            val cacheDir  = ctxAny.cacheDir
            val cacheBytes = dirSize(cacheDir)
            // dataDir contains cacheDir; subtract to get pure "data".
            val dataBytes  = (dirSize(dataDir) - cacheBytes).coerceAtLeast(0L)
            val totalBytes = apkBytes + dataBytes + cacheBytes

            // Paths first.
            row(ctx, it, "Files dir",   ctxAny.filesDir.absolutePath)
            row(ctx, it, "Cache dir",   cacheDir.absolutePath)
            row(ctx, it, "Data root",   dataDir.absolutePath)
            row(ctx, it, "External",    ctxAny.getExternalFilesDir(null)?.absolutePath ?: "—")
            row(ctx, it, "Trace log",   sizeStr(File(ctxAny.getExternalFilesDir(null), "trace/trace.log").length()))
            it.addView(small(ctx, "Breakdown — same buckets Android system settings shows:"))
            row(ctx, it, "Aplicación",  sizeStr(apkBytes))
            row(ctx, it, "Datos",       sizeStr(dataBytes))
            row(ctx, it, "Caché",       sizeStr(cacheBytes))
            row(ctx, it, "Total",       sizeStr(totalBytes))
        }

        section(ctx, column, "Permissions") {
            // Each row: friendly label + runtime grant state. Permissions
            // declared in AndroidManifest; here we just read the current
            // grant. Notifications gets a dedicated request button below
            // — the only one Android lets us trigger from inside the app.
            val perms = listOf(
                "Camera"            to android.Manifest.permission.CAMERA,
                "Microphone"        to android.Manifest.permission.RECORD_AUDIO,
                "Notifications"     to "android.permission.POST_NOTIFICATIONS",
                "Location (fine)"   to android.Manifest.permission.ACCESS_FINE_LOCATION,
                "Location (coarse)" to android.Manifest.permission.ACCESS_COARSE_LOCATION,
                "Nearby Devices"    to "android.permission.BLUETOOTH_SCAN",
                "Bluetooth Connect" to "android.permission.BLUETOOTH_CONNECT",
                "Contacts"          to android.Manifest.permission.READ_CONTACTS,
                "Music and Audio"   to "android.permission.READ_MEDIA_AUDIO",
                "Photos and Videos (img)" to "android.permission.READ_MEDIA_IMAGES",
                "Photos and Videos (vid)" to "android.permission.READ_MEDIA_VIDEO",
                "SMS"               to android.Manifest.permission.READ_SMS,
                "Phone"             to android.Manifest.permission.READ_PHONE_STATE,
            )
            for ((label, perm) in perms) {
                val state = runCatching {
                    androidx.core.content.ContextCompat.checkSelfPermission(ctxAny(), perm) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
                row(ctx, it, label, if (state) "✓ Granted" else "✗ Denied")
            }
            // Button row: request POST_NOTIFICATIONS via the standard
            // permission dialog (API 33+). On older Android levels
            // notifications are granted by default, so we just toast.
            it.addView(actionButton(ctx, "Request Notifications permission") {
                requestNotificationsPermission()
            })
            // Deeplink to the app's system-settings page so the user can
            // toggle the other permissions directly from there.
            it.addView(actionButton(ctx, "Open system app settings") {
                openAppSettings()
            })
        }

        section(ctx, column, "Sections (from build.json)") {
            val all = Sections.all()
            row(ctx, it, "Total",       all.size.toString())
            row(ctx, it, "Bottom-nav",  all.count { s -> s.bottomNav }.toString())
            row(ctx, it, "Aggregators", all.count { s -> s.isAggregator }.toString())
            row(ctx, it, "Default mode", Sections.defaultMode())
            row(ctx, it, "Default section", Sections.defaultSectionId())
        }

        section(ctx, column, "Dev control HTTP") {
            val prefs = DevControlPrefs(requireContext())
            row(ctx, it, "Endpoint", "http://127.0.0.1:${prefs.port}")
            row(ctx, it, "Token",    prefs.token)
            it.addView(small(ctx, "Bearer token — long-press to copy. Endpoints: /ping /info /state /haptic /goto /action /update /restart /logcat /trace /crashes"))
        }

        section(ctx, column, "Curl shortcuts") {
            val port = DevControlPrefs(requireContext()).port
            val tok  = DevControlPrefs(requireContext()).token
            row(ctx, it, "Logcat",  "curl http://127.0.0.1:$port/logcat?n=500")
            row(ctx, it, "Trace",   "curl http://127.0.0.1:$port/trace")
            row(ctx, it, "Crashes", "curl http://127.0.0.1:$port/crashes")
            row(ctx, it, "Haptic",  "curl -XPOST -H 'Authorization: Bearer $tok' 'http://127.0.0.1:$port/haptic?preset=gemini_stream'")
            row(ctx, it, "Update",  "curl -XPOST -H 'Authorization: Bearer $tok' http://127.0.0.1:$port/update")
            row(ctx, it, "Restart", "curl -XPOST -H 'Authorization: Bearer $tok' http://127.0.0.1:$port/restart")
        }
        return scroll
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun section(ctx: Context, host: LinearLayout, head: String, body: (LinearLayout) -> Unit) {
        host.addView(sectionHeader(ctx, head))
        val grp = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        host.addView(grp)
        body(grp)
    }

    private fun row(ctx: Context, host: LinearLayout, key: String, value: String) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(ctx).apply {
            text = key
            setTextColor(0xCCFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            layoutParams = LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        row.addView(TextView(ctx).apply {
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
        })
        host.addView(row)
    }

    private fun title(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFFE9D8FD.toInt())
        typeface = Typeface.DEFAULT_BOLD
        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        setPadding(0, 0, 0, dp(12))
    }

    private fun sectionHeader(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFF7C3AED.toInt())
        typeface = Typeface.DEFAULT_BOLD
        setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun small(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(0x99FFFFFF.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun actionButton(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF7C3AED.toInt())
        gravity = android.view.Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        layoutParams = lp
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun copy(ctx: Context, v: String) {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clip?.setPrimaryClip(ClipData.newPlainText("about", v))
    }

    private fun dirSize(dir: File): Long = runCatching {
        if (!dir.exists()) return@runCatching 0L
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun sizeStr(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GiB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576     -> "%.2f MiB".format(bytes / 1_048_576.0)
        bytes >= 1024          -> "%.1f KiB".format(bytes / 1024.0)
        else                   -> "$bytes B"
    }

    private fun fmtMillis(ms: Long): String =
        DateFormat.getDateTimeInstance().format(Date(ms))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = DevControlFragment() }
}
