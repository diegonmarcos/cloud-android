package com.diegonmarcos.superapp

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
        val prefs = LauncherThemePrefs(ctx)

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

        // Header
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
        val themes = remember(LauncherThemes.loadFromBuildConfig())
        val current = prefs.theme
        for (themeRow in themes) {
            root.addView(themeTile(ctx, themeRow, isSelected = themeRow.id == current.id) {
                prefs.theme = LauncherTheme.fromId(themeRow.id)
                // Rebuild the fragment so the selection highlight updates
                // immediately. MainActivity reads LauncherThemePrefs on
                // next state change — Phase 2 will hook the live swap.
                parentFragmentManager.beginTransaction()
                    .detach(this).attach(this)
                    .commit()
            })
            root.addView(spacer(ctx, dp(ctx, 8)))
        }

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

    private fun themeTile(
        ctx: android.content.Context,
        theme: LauncherThemes.Theme,
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
            text = (if (isSelected) "● " else "○ ") + theme.label
            setTextColor(0xFFFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        })
        if (theme.subtitle.isNotBlank()) {
            tile.addView(TextView(ctx).apply {
                text = theme.subtitle
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

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

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

    /** Trivial pass-through — avoids needing to import remember in a
     *  non-Compose Fragment while keeping the call site read like Compose. */
    private fun <T> remember(value: T): T = value

    companion object { fun newInstance() = LauncherConfigFragment() }
}

/** Parses build.json::ui.launcher_themes from the baked BuildConfig
 *  blob. New themes only need a build.json entry + a LauncherTheme
 *  enum case — no Kotlin list edits required here. */
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
}
