package com.diegonmarcos.ide

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

/**
 * The persistent "wrapper chrome" consistency top bar. Because we own every app
 * in the tree (Cloud-SuperApp → Cloud-IDE → forks), each one shows the same bar
 * with up-navigation chips to its ancestors, so the user is always one tap from
 * the parent hub and the root app.
 *
 * Data-driven: chips come from contract.navigation.parent_chain, baked into
 * BuildConfig.NAV_PARENTS_JSON_B64 (ordered root→leaf). [buildBar] returns chips
 * only for the entries ABOVE [selfPackage]. The hub (com.diegonmarcos.ide) shows
 * just "Cloud-SuperApp"; a fork would show "Cloud-SuperApp" + "Cloud-IDE".
 *
 * The forks can't depend on this class (separate APKs) — their wrapper patch
 * injects an equivalent bar reading the SAME baked NAV_PARENTS_JSON_B64, so the
 * contract stays the single source of truth.
 */
object NavBar {

    data class Parent(val name: String, val pkg: String)

    /** Ancestors of [selfPackage] = parent_chain entries before it (root→leaf). */
    fun ancestorsOf(selfPackage: String): List<Parent> {
        val all = parse()
        val idx = all.indexOfFirst { it.pkg == selfPackage }
        return if (idx <= 0) (if (idx < 0) all else emptyList()) else all.subList(0, idx)
    }

    /** Build the top bar for [activity] (whose own package is [selfPackage]).
     *  Returns null when there are no ancestors to show (e.g. the root app). */
    fun buildBar(activity: Activity, selfPackage: String): View? {
        val parents = ancestorsOf(selfPackage)
        if (parents.isEmpty()) return null

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        row.addView(TextView(activity).apply {
            text = "↑"
            textSize = 14f
            setPadding(dp(6), 0, dp(8), 0)
            alpha = 0.6f
        })
        for (p in parents) {
            row.addView(chip(activity, p, ::dp))
        }
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun chip(activity: Activity, p: Parent, dp: (Int) -> Int): TextView =
        TextView(activity).apply {
            text = p.name
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            // Subtle pill so the bar reads as chrome, not content.
            background = pill(dp(16))
            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)).also {
                it.rightMargin = dp(8); layoutParams = it
            }
            setOnClickListener { openParent(activity, p) }
        }

    private fun pill(radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(Color.argb(28, 128, 128, 128))
        }

    /** Bring the parent app forward. Parents (SuperApp, the IDE hub) keep their
     *  launcher icon, so a normal launch intent is correct here — only the FORKS
     *  are icon-less (and they're never parents). */
    private fun openParent(activity: Activity, p: Parent) {
        val launch = activity.packageManager.getLaunchIntentForPackage(p.pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            activity.startActivity(launch)
        } else {
            Toast.makeText(activity, "${p.name} not installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parse(): List<Parent> = runCatching {
        val json = String(Base64.decode(BuildConfig.NAV_PARENTS_JSON_B64, Base64.DEFAULT))
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Parent(o.getString("name"), o.getString("package"))
        }
    }.getOrDefault(emptyList())
}
