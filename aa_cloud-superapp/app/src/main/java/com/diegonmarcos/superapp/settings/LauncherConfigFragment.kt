package com.diegonmarcos.superapp.settings
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.system.BackgroundOrchestrator
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.apps.PhoneAppsFragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import org.json.JSONArray

/**
 * Configs → Launcher — theme picker for the SuperApp's Home / Launcher
 * mode. The themes list is data-driven from
 * build.json::ui.launcher_themes (baked into BuildConfig as a base64
 * JSON blob). Tapping a theme persists it via [LauncherThemePrefs] and
 * MainActivity re-reads on next render to apply the new theme's
 * chrome.
 *
 * Below the picker there's a "Set as default launcher" call-to-action
 * that fires the system Home settings intent — Android handles the
 * actual chooser; we just navigate the user there.
 */
class LauncherConfigFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val themePrefs = LauncherThemePrefs(ctx)
        val profilePrefs = LauncherProfilePrefs(ctx)

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 16); setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)

        // ── Profiles section ───────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text = "Profile"
            setTextColor(0xFFFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setPadding(0, 0, 0, dp(ctx, 8))
        })
        root.addView(TextView(ctx).apply {
            text = "Personal / Work / Guest. The picked profile is the " +
                "foundation for filtering which apps + folders the Phone " +
                "tab will surface (wired in a follow-up patch)."
            setTextColor(0xAAFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            setPadding(0, 0, 0, dp(ctx, 16))
        })
        val profiles = LauncherProfiles.loadFromBuildConfig()
        val currentProfile = profilePrefs.profile
        for (profileRow in profiles) {
            root.addView(genericTile(
                ctx,
                label    = profileRow.label,
                subtitle = profileRow.subtitle,
                isSelected = profileRow.id == currentProfile.id,
            ) {
                profilePrefs.profile = LauncherProfile.fromId(profileRow.id)
                rerender()
            })
            root.addView(spacer(ctx, dp(ctx, 8)))
        }

        // ── Theme section ──────────────────────────────────────────
        root.addView(spacer(ctx, dp(ctx, 24)))
        root.addView(TextView(ctx).apply {
            text = "Launcher theme"
            setTextColor(0xFFFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setPadding(0, 0, 0, dp(ctx, 8))
        })
        root.addView(TextView(ctx).apply {
            text = "Pick the look the home screen uses when the SuperApp " +
                "is set as the Android default launcher."
            setTextColor(0xAAFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            setPadding(0, 0, 0, dp(ctx, 16))
        })

        // Theme tiles — data-driven from BuildConfig.
        val themes = LauncherThemes.loadFromBuildConfig()
        val current = themePrefs.theme
        for (themeRow in themes) {
            root.addView(genericTile(
                ctx,
                label    = themeRow.label,
                subtitle = themeRow.subtitle,
                isSelected = themeRow.id == current.id,
            ) {
                themePrefs.theme = LauncherTheme.fromId(themeRow.id)
                (activity as? MainActivity)?.notifyLauncherThemeChanged()
                rerender()
            })
            root.addView(spacer(ctx, dp(ctx, 8)))
        }

        // ── Screensaver section ────────────────────────────────────
        val settingsPrefs = LauncherSettingsPrefs(ctx)
        root.addView(spacer(ctx, dp(ctx, 24)))
        root.addView(sectionHeader(ctx, "Screensaver",
            "Which screensaver the floating-nav ‘Screensaver’ action shows, and the idle auto-start timer."))
        val currentSaver = settingsPrefs.screensaver
        for (saver in LauncherSettingsPrefs.Config.screensavers) {
            root.addView(genericTile(ctx, saver.label, saver.subtitle, saver.id == currentSaver) {
                settingsPrefs.screensaver = saver.id
                rerender()
            })
            root.addView(spacer(ctx, dp(ctx, 8)))
        }
        // Idle auto-start timer (seconds; 0 = never).
        val st = LauncherSettingsPrefs.Config.screensaverTimeout
        root.addView(sliderRow(ctx, st.label, st.subtitle, st.min, st.max,
            settingsPrefs.screensaverTimeout) { v -> settingsPrefs.screensaverTimeout = v })

        // Split the toggles data-drivenly: the ones flagged battery:true in
        // build.json are the actual power drains (continuous animations + the
        // 10s live-stats poll) → grouped under "Battery Hunger Ones" with a
        // master switch; the rest stay under "Others". Add a dragger = one
        // `"battery": true` in build.json, no code change.
        val allToggles = LauncherSettingsPrefs.Config.toggles
        val batteryOnes = allToggles.filter { it.battery }
        val otherOnes   = allToggles.filter { !it.battery }

        // ── Battery Hunger Ones ────────────────────────────────────
        if (batteryOnes.isNotEmpty()) {
            root.addView(spacer(ctx, dp(ctx, 24)))
            root.addView(sectionHeader(ctx, "Battery Hunger Ones",
                "Continuous animations + live system stats — the effects that cost battery. Off = leaner."))
            val allOn = batteryOnes.all { settingsPrefs.toggle(it.id) }
            root.addView(toggleRow(ctx, "All battery-hungry effects",
                "One switch to flip every effect below on or off.", allOn) { on ->
                batteryOnes.forEach { settingsPrefs.setToggle(it.id, on) }
                (activity as? MainActivity)?.notifyLauncherThemeChanged()
                com.diegonmarcos.superapp.configs.ConstellationWorker.start(requireContext()) // re-apply fleet_check
                rerender() // reflect the child switches
            })
            root.addView(spacer(ctx, dp(ctx, 8)))
            for (t in batteryOnes) {
                root.addView(toggleRow(ctx, t.label, t.subtitle, settingsPrefs.toggle(t.id)) { on ->
                    settingsPrefs.setToggle(t.id, on)
                    (activity as? MainActivity)?.notifyLauncherThemeChanged()
                    com.diegonmarcos.superapp.configs.ConstellationWorker.start(requireContext()) // re-apply fleet_check
                    rerender() // keep the master switch in sync
                })
                root.addView(spacer(ctx, dp(ctx, 8)))
            }
        }

        // ── Others section ─────────────────────────────────────────
        root.addView(spacer(ctx, dp(ctx, 24)))
        root.addView(sectionHeader(ctx, "Others",
            "Haptics, brightness and eye protection."))
        for (t in otherOnes) {
            root.addView(toggleRow(ctx, t.label, t.subtitle, settingsPrefs.toggle(t.id)) { on ->
                settingsPrefs.setToggle(t.id, on)
                // Eye protection = the ANDROID SYSTEM night-light (blue-light
                // filter), NOT a custom overlay — open its settings to enable.
                if (t.id == "eye_protection" && on) {
                    runCatching { startActivity(Intent("android.settings.NIGHT_DISPLAY_SETTINGS")) }
                        .onFailure { runCatching { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } }
                }
                // Re-apply launcher chrome so stars/cube/pets pick up the change.
                (activity as? MainActivity)?.notifyLauncherThemeChanged()
            })
            root.addView(spacer(ctx, dp(ctx, 8)))
        }
        // Screen brightness (device-wide → needs WRITE_SETTINGS)
        val b = LauncherSettingsPrefs.Config.brightness
        root.addView(sliderRow(ctx, b.label, b.subtitle, b.min, b.max,
            settingsPrefs.brightness.let { if (it < b.min) b.min else it }) { v ->
            settingsPrefs.brightness = v
            applySystemBrightness(ctx, v)
        })

        // "Set as default launcher" CTA
        root.addView(spacer(ctx, dp(ctx, 16)))
        root.addView(TextView(ctx).apply {
            text = if (isDefaultLauncher(ctx))
                "✓ SuperApp is the active default launcher."
            else
                "SuperApp is NOT the default launcher yet."
            setTextColor(0xFFFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            setPadding(0, 0, 0, dp(ctx, 8))
        })
        root.addView(TextView(ctx).apply {
            text = "Set as default launcher →"
            setTextColor(0xFF7C3AED.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            setBackgroundColor(0x227C3AEDL.toInt())
            isClickable = true; isFocusable = true
            setOnClickListener {
                Haptics.tap(it)
                runCatching {
                    startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                }.onFailure {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                    }
                }
            }
        })

        return scroll
    }

    /** Shared "selectable card" row used by both the Profiles and the
     *  Themes pickers — label up top, optional subtitle beneath,
     *  selected-state filled in brand purple, unselected on 13% white. */
    private fun genericTile(
        ctx: android.content.Context,
        label: String,
        subtitle: String,
        isSelected: Boolean,
        onClick: () -> Unit,
    ): View {
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 14); setPadding(pad, pad, pad, pad)
            setBackgroundColor(if (isSelected) 0x447C3AED.toInt() else 0x22FFFFFFL.toInt())
            isClickable = true; isFocusable = true
            setOnClickListener {
                Haptics.tap(it)
                onClick()
            }
        }
        tile.addView(TextView(ctx).apply {
            text = (if (isSelected) "● " else "○ ") + label
            setTextColor(0xFFFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        })
        if (subtitle.isNotBlank()) {
            tile.addView(TextView(ctx).apply {
                text = subtitle
                setTextColor(0xAAFFFFFFL.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, dp(ctx, 4), 0, 0)
            })
        }
        return tile
    }

    private fun spacer(ctx: android.content.Context, h: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    /** Section title + caption block — matches the hand-rolled headers above. */
    private fun sectionHeader(ctx: android.content.Context, title: String, subtitle: String): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply {
                text = title
                setTextColor(0xFFFFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Headline)
                setPadding(0, 0, 0, dp(ctx, 8))
            })
            addView(TextView(ctx).apply {
                text = subtitle
                setTextColor(0xAAFFFFFFL.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Body2)
                setPadding(0, 0, 0, dp(ctx, 16))
            })
        }

    /** A label/subtitle + right-aligned switch row, persisted on toggle. */
    private fun toggleRow(
        ctx: android.content.Context,
        label: String, subtitle: String, checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(ctx, 14); setPadding(pad, pad, pad, pad)
        setBackgroundColor(0x22FFFFFFL.toInt())
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = label
                setTextColor(0xFFFFFFFFL.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            })
            if (subtitle.isNotBlank()) addView(TextView(ctx).apply {
                text = subtitle
                setTextColor(0xAAFFFFFFL.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            })
        })
        addView(SwitchCompat(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { v, isOn -> Haptics.tap(v); onChange(isOn) }
        })
    }

    /** A label + SeekBar row. [value] pre-positions the thumb; onChange fires
     *  on release so we don't spam writes while dragging. */
    private fun sliderRow(
        ctx: android.content.Context,
        label: String, subtitle: String, min: Int, max: Int, value: Int,
        onChange: (Int) -> Unit,
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(ctx, 14); setPadding(pad, pad, pad, pad)
        setBackgroundColor(0x22FFFFFFL.toInt())
        addView(TextView(ctx).apply {
            text = label
            setTextColor(0xFFFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        })
        if (subtitle.isNotBlank()) addView(TextView(ctx).apply {
            text = subtitle
            setTextColor(0xAAFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, 0, 0, dp(ctx, 4))
        })
        addView(SeekBar(ctx).apply {
            this.min = min
            this.max = max
            progress = value.coerceIn(min, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { onChange(sb.progress) }
            })
        })
    }

    /** Device-wide brightness via Settings.System (manual mode). Needs the
     *  WRITE_SETTINGS special-access grant — if absent, route the user to the
     *  grant screen and apply on their next adjustment. value<min = untouched. */
    private fun applySystemBrightness(ctx: android.content.Context, value: Int) {
        if (value < 0) return
        if (!Settings.System.canWrite(ctx)) {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + ctx.packageName)))
            }
            return
        }
        runCatching {
            Settings.System.putInt(ctx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(0, 255))
        }
    }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /** Force the fragment to rebuild so picker selections refresh. detach+attach
     *  in ONE transaction is collapsed to a no-op by the FragmentManager (the
     *  net state is unchanged) — splitting into two commitNow() calls guarantees
     *  onCreateView re-runs and the "● / ○" selection state updates. */
    private fun rerender() {
        runCatching {
            val fm = parentFragmentManager
            fm.beginTransaction().detach(this).commitNow()
            fm.beginTransaction().attach(this).commitNow()
        }
    }

    /** True when the SuperApp's MainActivity is currently the resolved
     *  default Home Screen handler. The picker uses this to surface a
     *  live "active / not active" hint above the CTA. */
    private fun isDefaultLauncher(ctx: android.content.Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolved = ctx.packageManager.resolveActivity(intent, 0)
        return resolved?.activityInfo?.packageName == ctx.packageName
    }

    companion object { fun newInstance() = LauncherConfigFragment() }
}

/** Parses build.json::ui.launcher_themes from the baked BuildConfig
 *  blob. New themes only need a build.json entry + a LauncherTheme
 *  enum case — no Kotlin list edits required here.
 *
 *  Also hosts the data-driven [Features] decoder used by MainActivity
 *  / BackgroundOrchestrator to apply each theme's behavior (kept in
 *  this file so the picker code and the runtime code share one
 *  registry — no risk of two parallel parsers drifting). */
object LauncherThemes {
    data class Theme(
        val id: String,
        val label: String,
        val subtitle: String,
        val default: Boolean,
    )

    fun loadFromBuildConfig(): List<Theme> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_LAUNCHER_THEMES_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            Theme(
                id       = o.optString("id"),
                label    = o.optString("label"),
                subtitle = o.optString("subtitle"),
                default  = o.optBoolean("default", false),
            )
        }
    }.getOrDefault(emptyList())

    /** Mirrors the keys in build.json::ui.launcher_themes[*].features. */
    data class Features(
        val home3d:            Boolean,
        val bottomNav:         Boolean,
        val dynamicIsland:     Boolean,
        val animations:        Boolean,
        val drawer:            Boolean,
        val oledBlack:         Boolean,
        val grid:              String,
        val backgroundPause:   Boolean,
        val wireguardRequired: Boolean,
    ) {
        companion object {
            val SAFE_DEFAULT = Features(
                home3d            = true,
                bottomNav         = true,
                dynamicIsland     = true,
                animations        = true,
                drawer            = true,
                oledBlack         = false,
                grid              = "default",
                backgroundPause   = false,
                wireguardRequired = false,
            )
        }
    }

    private val featuresById: Map<String, Features> by lazy { parseFeatures() }

    fun featuresFor(themeId: String): Features = featuresById[themeId] ?: Features.SAFE_DEFAULT
    fun featuresFor(theme: LauncherTheme): Features = featuresFor(theme.id)

    private fun parseFeatures(): Map<String, Features> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_LAUNCHER_THEMES_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val out = mutableMapOf<String, Features>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val f = o.optJSONObject("features") ?: org.json.JSONObject()
            out[id] = Features(
                home3d            = f.optBoolean("home_3d",            true),
                bottomNav         = f.optBoolean("bottom_nav",         true),
                dynamicIsland     = f.optBoolean("dynamic_island",     true),
                animations        = f.optBoolean("animations",         true),
                drawer            = f.optBoolean("drawer",             true),
                oledBlack         = f.optBoolean("oled_black",         false),
                grid              = f.optString("grid",                "default"),
                backgroundPause   = f.optBoolean("background_pause",   false),
                wireguardRequired = f.optBoolean("wireguard_required", false),
            )
        }
        out
    }.getOrDefault(emptyMap())
}

/** Parses build.json::ui.launcher_profiles from the baked BuildConfig
 *  blob. Same shape as [LauncherThemes]. Add a profile via build.json
 *  + LauncherProfile enum — no other Kotlin edits needed.
 *
 *  Also hosts the [Behavior] decoder + [allowedPackagesFor] used by
 *  PhoneAppsFragment / MainActivity to enforce per-profile lockdowns. */
object LauncherProfiles {
    data class Profile(
        val id: String,
        val label: String,
        val subtitle: String,
        val default: Boolean,
    )

    fun loadFromBuildConfig(): List<Profile> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_LAUNCHER_PROFILES_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            Profile(
                id       = o.optString("id"),
                label    = o.optString("label"),
                subtitle = o.optString("subtitle"),
                default  = o.optBoolean("default", false),
            )
        }
    }.getOrDefault(emptyList())

    /** Mirrors build.json::ui.launcher_profiles[*].behavior. */
    data class Behavior(
        val appFilter:     String,        // "all" | "whitelist"
        val whitelist:     List<String>,
        val wireguard:     String,        // "on" | "off"
        val allowIntents:  Any,           // "all" | List<String>
    ) {
        val isLockdown: Boolean get() = appFilter == "whitelist"
        val wireguardOff: Boolean get() = wireguard.equals("off", ignoreCase = true)

        companion object {
            val PASSTHROUGH = Behavior(
                appFilter    = "all",
                whitelist    = emptyList(),
                wireguard    = "on",
                allowIntents = "all",
            )
        }
    }

    private val behaviorById: Map<String, Behavior> by lazy { parseBehaviors() }

    fun behaviorFor(profileId: String): Behavior = behaviorById[profileId] ?: Behavior.PASSTHROUGH
    fun behaviorFor(profile: LauncherProfile): Behavior = behaviorFor(profile.id)

    /** Resolve the set of allowed package names for the active profile.
     *  Returns null when the active profile is passthrough — caller
     *  should NOT filter. Returns a possibly-empty set otherwise. */
    fun allowedPackagesFor(context: android.content.Context, profile: LauncherProfile): Set<String>? {
        val b = behaviorFor(profile)
        if (!b.isLockdown) return null
        val out = mutableSetOf<String>()
        b.whitelist.forEach { tag -> out.addAll(packagesForTag(context, tag)) }
        return out
    }

    private fun packagesForTag(context: android.content.Context, tag: String): Set<String> = when (tag) {
        "browser" -> browserPackages(context)
        else      -> setOf(tag)
    }

    private fun browserPackages(context: android.content.Context): Set<String> {
        val pm = context.packageManager
        val probe = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("http://example.com"),
        )
        val flags = android.content.pm.PackageManager.MATCH_ALL or
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        val matches = try { pm.queryIntentActivities(probe, flags) } catch (_: Throwable) { emptyList() }
        return matches.mapNotNull { it.activityInfo?.packageName }.toSet()
    }

    private fun parseBehaviors(): Map<String, Behavior> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_LAUNCHER_PROFILES_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val out = mutableMapOf<String, Behavior>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val b = o.optJSONObject("behavior") ?: org.json.JSONObject()
            val whitelist = mutableListOf<String>()
            val wlArr = b.optJSONArray("whitelist") ?: JSONArray()
            for (j in 0 until wlArr.length()) {
                val s = wlArr.optString(j); if (s.isNotBlank()) whitelist.add(s)
            }
            val rawAllow = b.opt("allow_intents")
            val allow: Any = when (rawAllow) {
                is JSONArray -> (0 until rawAllow.length()).mapNotNull {
                    val s = rawAllow.optString(it); if (s.isBlank()) null else s
                }
                is String -> rawAllow
                else -> "all"
            }
            out[id] = Behavior(
                appFilter    = b.optString("app_filter", "all"),
                whitelist    = whitelist,
                wireguard    = b.optString("wireguard",  "on"),
                allowIntents = allow,
            )
        }
        out
    }.getOrDefault(emptyMap())
}
