package com.diegonmarcos.superapp.apptabs

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * App Tabs — 2-column GRID of the last [AppTabPrefs.cap] entries the
 * user has opened. Mirrors libs:browser's GRID mode but renders
 * SuperApp sections / pages / external apps instead of URL tabs.
 *
 * Tap a card → host activity handles re-navigation via the
 * [AppTabsHost] callback (so this module doesn't reach back into
 * app/'s MainActivity by name). MainActivity implements AppTabsHost
 * and routes the tap to goSection / openSectionPage / startActivity
 * as appropriate.
 *
 * No drill / no DETAIL mode — App Tabs is purely an LRU launcher
 * shelf; tapping a card switches; long-press could drop in a future
 * patch for explicit eviction.
 */
class AppTabsFragment : Fragment() {

    private lateinit var prefs: AppTabPrefs
    private lateinit var rootContainer: FrameLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = AppTabPrefs(ctx)
        rootContainer = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0xFF0B0414.toInt())
        }
        showGrid()
        return rootContainer
    }

    override fun onResume() {
        super.onResume()
        // Re-render on resume — the LRU contents typically just got
        // updated by whatever navigation brought the user back here.
        showGrid()
    }

    private fun showGrid() {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12); setPadding(pad, pad, pad, pad)
        }

        val header = TextView(ctx).apply {
            text = "Tabs"
            setTextColor(0xFFE9D8FD.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            val pad = dp(8); setPadding(pad, dp(4), pad, dp(12))
        }
        column.addView(header)

        val entries = prefs.all()
        if (entries.isEmpty()) {
            column.addView(TextView(ctx).apply {
                text = "No recent entries yet. Open a SuperApp section, a page, or an external app from the Phone tab — the last ${AppTabPrefs.cap} land here."
                setTextColor(0xCCFFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                alpha = 0.7f
                val pad = dp(20); setPadding(pad, pad, pad, pad)
            })
        } else {
            val grid = GridLayout(ctx).apply {
                columnCount = 2
                useDefaultMargins = false
            }
            entries.forEach { grid.addView(entryCard(ctx, it)) }
            column.addView(grid)
        }

        scroll.addView(column)
        rootContainer.removeAllViews()
        rootContainer.addView(scroll)
    }

    private fun entryCard(ctx: Context, entry: AppTabPrefs.Entry): View {
        val cardW = (resources.displayMetrics.widthPixels / 2) - dp(20)
        val cardH = dp(160)
        val card = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(0xFF1A0033.toInt())
                setStroke(1, 0x55B794F4)
            }
            layoutParams = GridLayout.LayoutParams().apply {
                width = cardW; height = cardH
                val m = dp(6); setMargins(m, m, m, m)
            }
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            val pad = dp(12); setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        val label = TextView(ctx).apply {
            text = displayLabel(entry)
            setTextColor(0xFFEDE7FF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            gravity = android.view.Gravity.CENTER
            val mt = dp(8); setPadding(0, mt, 0, 0)
            maxLines = 2
        }
        val sub = TextView(ctx).apply {
            text = displaySubtitle(entry)
            setTextColor(0x99FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            gravity = android.view.Gravity.CENTER
            alpha = 0.7f
            maxLines = 1
        }

        // Resolve icon. SuperApp-side iconName is a drawable resource
        // name owned by app/'s R; external apps use PackageManager.
        when (entry) {
            is AppTabPrefs.Entry.SectionEntry,
            is AppTabPrefs.Entry.PageEntry,
            is AppTabPrefs.Entry.TargetEntry -> {
                val iconName = when (entry) {
                    is AppTabPrefs.Entry.SectionEntry -> entry.iconName
                    is AppTabPrefs.Entry.PageEntry    -> entry.iconName
                    is AppTabPrefs.Entry.TargetEntry  -> entry.iconName
                    else                              -> ""
                }
                val id = ctx.resources.getIdentifier(iconName, "drawable", ctx.packageName)
                if (id != 0) icon.setImageResource(id)
            }
            is AppTabPrefs.Entry.ExternalAppEntry -> {
                runCatching {
                    icon.setImageDrawable(ctx.packageManager.getApplicationIcon(entry.packageName))
                }
            }
        }

        content.addView(icon)
        content.addView(label)
        content.addView(sub)
        card.addView(content)

        card.setOnClickListener { (requireActivity() as? AppTabsHost)?.onAppTabPicked(entry) }
        return card
    }

    private fun displayLabel(e: AppTabPrefs.Entry): String = when (e) {
        is AppTabPrefs.Entry.SectionEntry     -> e.label.ifBlank { e.sectionId }
        is AppTabPrefs.Entry.PageEntry        -> e.label.ifBlank { "${e.sectionId}/${e.pageId}" }
        is AppTabPrefs.Entry.ExternalAppEntry -> e.label.ifBlank { e.packageName }
        is AppTabPrefs.Entry.TargetEntry      -> e.label.ifBlank { e.target }
    }

    private fun displaySubtitle(e: AppTabPrefs.Entry): String = when (e) {
        is AppTabPrefs.Entry.SectionEntry     -> "section"
        is AppTabPrefs.Entry.PageEntry        -> "page · ${e.sectionId}"
        is AppTabPrefs.Entry.ExternalAppEntry -> "app · ${e.packageName.take(28)}"
        is AppTabPrefs.Entry.TargetEntry      -> "page"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun newInstance(): AppTabsFragment = AppTabsFragment()
    }
}

/** Activity callback contract — implemented by MainActivity so the
 *  fragment can hand a recorded entry back for re-navigation without
 *  reaching back into app/'s class names. */
interface AppTabsHost {
    fun onAppTabPicked(entry: AppTabPrefs.Entry)
}
